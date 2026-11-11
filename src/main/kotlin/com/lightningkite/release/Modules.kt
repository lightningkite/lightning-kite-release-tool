package com.lightningkite.release

import java.io.File
import java.security.MessageDigest

/** `group:artifact`. */
typealias Coordinate = String

/**
 * A published module: a directory whose `gradle.properties` has a `version` line.  Its artifact is the directory
 * name and its Gradle path follows its directory path.
 */
data class Module(val directory: File, val gradlePath: String, val group: String, val artifact: String, val written: Version) {
    val coordinate: Coordinate get() = "$group:$artifact"
    val propertiesFile: File get() = directory.resolve("gradle.properties")

    /** Where a Maven repository keeps this module's versions. */
    val mavenDirectory: String get() = "${group.replace('.', '/')}/$artifact"

    /** The path every file of one version starts with: add `.module`, `-release.properties`, ... */
    fun mavenPath(version: String): String = "$mavenDirectory/$version/$artifact-$version"

    /** How `--rebuild` may name it. */
    fun isNamed(name: String): Boolean = name == artifact || name == coordinate || name == gradlePath

    /** `:a:b:task`, or `:task` for a repo whose root project is the module. */
    fun task(name: String): String = if (gradlePath == ":") ":$name" else "$gradlePath:$name"
}

// Spaces and tabs only around the value: \s would also swallow the line break after it.
private val versionLine = Regex("""(?m)^version[ \t]*=[ \t]*(\S+)[ \t]*$""")
private val groupLine = Regex("""(?m)^group[ \t]*=[ \t]*(\S+)[ \t]*$""")
private val skippedDirectories = setOf("build", "node_modules", "kotlin-js-store", "buildSrc", "src")

fun discoverModules(root: File): List<Module> = root.listFiles()
    .filter { it.isDirectory }
    .map { it.resolve("gradle.properties") }
    .filter { it.exists() }
    .mapNotNull { file ->
        val text = file.readText()
        val version = versionLine.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
        val directory = file.parentFile
        Module(
            directory = directory,
            gradlePath = ":" + directory.relativeTo(root).invariantSeparatorsPath.replace('/', ':'),
            group = groupLine.find(text)?.groupValues?.get(1) ?: throw IllegalStateException("$file has a version but no group"),
            artifact = directory.name,
            // `-local` is what a crashed local run leaves behind until the journal restores it.
            written = Version.parse(version.removeSuffix("-local")),
        )
    }
    .sortedBy { it.gradlePath }
    .toList()

/** [properties] with its `version` line set to [version]. */
fun withVersion(properties: String, version: String): String {
    require(versionLine.containsMatchIn(properties)) { "No version line to replace" }
    return versionLine.replace(properties) { "version=$version" }
}

private fun hash(files: List<Pair<String, ByteArray>>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for ((path, bytes) in files.sortedBy { it.first }) {
        digest.update(path.toByteArray())
        digest.update(0)
        digest.update(bytes)
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun File.withoutVersionLine(): ByteArray = versionLine.replace(readText(), "").toByteArray()

/**
 * What a module's release is made from: its `build.gradle.kts`, its `gradle.properties` without the version line,
 * and every source set not named `*test*`.
 */
fun inputsHash(module: File): String {
    val sources = module.resolve("src").listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.contains("test", ignoreCase = true) }
        .flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.isFile && it.name != ".DS_Store" } }
        .map { it.relativeTo(module).invariantSeparatorsPath to it.readBytes() }
    val script = module.resolve("build.gradle.kts").takeIf { it.exists() }?.let { "build.gradle.kts" to it.readBytes() }
    return hash(sources + listOfNotNull(script) + ("gradle.properties" to module.resolve("gradle.properties").withoutVersionLine()))
}

/**
 * The shared build logic.  Not a release input: recorded per release so `lk plan` can report modules built with
 * older logic, which `--rebuild` then releases deliberately.
 */
fun buildLogicHash(root: File, rootIsModule: Boolean): String {
    val buildSrc = root.resolve("buildSrc").walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
    val plain = listOf("settings.gradle.kts", "gradle/wrapper/gradle-wrapper.properties", "gradle/libs.versions.toml")
        .plus(if (rootIsModule) listOf() else listOf("build.gradle.kts"))
        .map { root.resolve(it) }.filter { it.exists() }
        .map { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
    val properties = root.resolve("gradle.properties").takeIf { it.exists() }?.let { "gradle.properties" to it.withoutVersionLine() }
    return hash(buildSrc.toList() + plain + listOfNotNull(properties))
}
