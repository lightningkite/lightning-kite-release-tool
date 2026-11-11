package com.lightningkite.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Gradle module metadata (a `.module` file), as far as dependencies go. */
@Serializable
private class ModuleMetadata(val variants: List<Variant> = listOf()) {
    @Serializable
    class Variant(
        val attributes: Map<String, JsonPrimitive> = mapOf(),
        val dependencies: List<Dependency> = listOf(),
        /** Present on variants that aren't the library itself, such as test fixtures. */
        val capabilities: List<JsonObject>? = null,
        /** A Kotlin Multiplatform root module points at each target's own module instead of listing its dependencies. */
        @SerialName("available-at") val availableAt: AvailableAt? = null,
    ) {
        val usage: String? get() = attributes["org.gradle.usage"]?.content
        val isTheLibrary: Boolean get() = attributes["org.gradle.category"]?.content == "library" && capabilities == null
    }

    @Serializable
    class AvailableAt(val url: String)

    @Serializable
    class Dependency(val group: String, val module: String, val version: VersionConstraint? = null, val attributes: Map<String, JsonPrimitive> = mapOf()) {
        val isPlatform: Boolean get() = attributes["org.gradle.category"]?.content?.endsWith("platform") == true
    }

    @Serializable
    class VersionConstraint(val strictly: String? = null, val requires: String? = null, val prefers: String? = null)
}

private val json = Json { ignoreUnknownKeys = true }

/** One target's dependencies.  [runtime] is null for native targets, which publish no runtime variant. */
private class TargetDependencies(val api: Map<Coordinate, String>, val runtime: Map<Coordinate, String>?)

/**
 * The direct dependencies of a module Gradle just published into [repository] (a staging directory or mavenLocal),
 * with the scope its users see.  A dependency is `api` when a target's api variant lists it, and `implementation`
 * when only runtime variants do.  Native targets list everything as api, so a dependency declared for native
 * targets alone counts as `api`, the cautious reading.
 *
 * Not seen: dependencies whose version a BOM supplies, and KSP processors, which aren't dependencies of the
 * published module at all.
 */
fun publishedDependencies(repository: File, module: Module, version: String): Map<Coordinate, Dependency> {
    val rootFile = repository.resolve("${module.mavenPath(version)}.module")
    check(rootFile.isFile) { "${module.gradlePath} was published, but $rootFile doesn't exist; its artifact must be named after its directory" }
    fun variants(file: File) = json.decodeFromString<ModuleMetadata>(file.readText()).variants
    // A plain JVM module is its own only target.
    val targetFiles = variants(rootFile).mapNotNull { it.availableAt?.url }.distinct().map { rootFile.parentFile.resolve(it).normalize() }.ifEmpty { listOf(rootFile) }

    val targets = targetFiles.map { file ->
        check(file.isFile) { "${module.gradlePath}: $rootFile points at $file, which doesn't exist" }
        val library = variants(file).filter { it.isTheLibrary }
        fun dependencies(vararg usages: String): Map<Coordinate, String>? = library.filter { it.usage in usages }.takeIf { it.isNotEmpty() }
            ?.flatMap { it.dependencies }?.filter { !it.isPlatform }
            ?.mapNotNull { dependency -> dependency.version?.run { strictly ?: requires ?: prefers }?.let { "${dependency.group}:${dependency.module}" to it } }
            ?.toMap()
        TargetDependencies(api = dependencies("java-api", "kotlin-api").orEmpty(), runtime = dependencies("java-runtime", "kotlin-runtime"))
    }

    val (native, withRuntime) = targets.partition { it.runtime == null }
    val api = withRuntime.flatMap { it.api.entries }.associate { it.toPair() }
    val runtime = withRuntime.flatMap { it.runtime!!.entries }.associate { it.toPair() }
    val nativeOnly = native.flatMap { it.api.entries }.associate { it.toPair() } - runtime.keys
    return nativeOnly.mapValues { Dependency(it.value, Scope.Api) } +
        runtime.mapValues { Dependency(it.value, Scope.Implementation) } +
        api.mapValues { Dependency(it.value, Scope.Api) }
}
