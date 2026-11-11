package com.lightningkite.release

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleMetadataTest {
    private val repository: File = Files.createTempDirectory("published").toFile()

    private fun write(path: String, text: String) = repository.resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    private fun dependency(coordinate: String, version: String?) = buildString {
        append("""{"group":"${coordinate.substringBefore(':')}","module":"${coordinate.substringAfter(':')}"""")
        if (version != null) append(""","version":{"requires":"$version"}""")
        append("}")
    }

    private fun variant(usage: String, vararg dependencies: String, category: String = "library", extra: String = "") =
        """{"name":"x","attributes":{"org.gradle.category":"$category","org.gradle.usage":"$usage","org.gradle.jvm.version":8},"dependencies":[${dependencies.joinToString(",")}]$extra}"""

    private fun metadata(vararg variants: String) = """{"formatVersion":"1.1","variants":[${variants.joinToString(",")}]}"""

    @Test
    fun jvmModule() {
        write(
            "com/example/lib/1000.0.0/lib-1000.0.0.module",
            metadata(
                variant("java-api", dependency("com.example:base", "1000.0.0"), dependency("org.jetbrains.kotlin:kotlin-stdlib", "2.4.21")),
                variant(
                    "java-runtime",
                    dependency("com.example:base", "1000.0.0"), dependency("org.jetbrains.kotlin:kotlin-stdlib", "2.4.21"),
                    dependency("io.ktor:ktor-client-core", "3.6.1"), dependency("io.netty:netty-codec-http", null),
                    """{"group":"io.netty","module":"netty-bom","version":{"requires":"4.2.0"},"attributes":{"org.gradle.category":"platform"}}""",
                ),
                variant("java-runtime", category = "documentation"),
                variant("java-api", dependency("org.jetbrains.kotlin:kotlin-test", "2.4.21"), extra = ""","capabilities":[{"group":"com.example","name":"lib-test-fixtures","version":"1000.0.0"}]"""),
            ),
        )
        val module = Module(repository.resolve("lib"), ":lib", "com.example", "lib", Version(1000, 0, 0))
        assertEquals(
            mapOf(
                "com.example:base" to Dependency("1000.0.0", Scope.Api),
                "org.jetbrains.kotlin:kotlin-stdlib" to Dependency("2.4.21", Scope.Api),
                "io.ktor:ktor-client-core" to Dependency("3.6.1", Scope.Implementation),
            ),
            publishedDependencies(repository, module, "1000.0.0"),
        )
    }

    @Test
    fun multiplatformModuleIsReadFromItsTargets() {
        fun pointer(target: String) = """{"name":"x","attributes":{"org.gradle.category":"library","org.gradle.usage":"kotlin-api"},"available-at":{"url":"../../kmp-$target/1.0.0-local/kmp-$target-1.0.0-local.module","group":"com.example","module":"kmp-$target","version":"1.0.0-local"}}"""
        write(
            "com/example/kmp/1.0.0-local/kmp-1.0.0-local.module",
            metadata(
                // The common metadata variant mixes api and implementation, so it must not be read.
                variant("kotlin-metadata", dependency("com.example:only-in-metadata", "1.0.0")),
                pointer("jvm"), pointer("js"), pointer("iosarm64"),
            ),
        )
        write(
            "com/example/kmp-jvm/1.0.0-local/kmp-jvm-1.0.0-local.module",
            metadata(
                variant("java-api", dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core", "1.11.0")),
                variant("java-runtime", dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core", "1.11.0"), dependency("org.jetbrains.kotlinx:atomicfu", "0.29.0")),
            ),
        )
        write(
            "com/example/kmp-js/1.0.0-local/kmp-js-1.0.0-local.module",
            metadata(
                variant("kotlin-api", """{"group":"org.jetbrains.kotlinx","module":"kotlinx-datetime","version":{"strictly":"0.8.0","requires":"0.8.0"}}"""),
                variant("kotlin-runtime", dependency("org.jetbrains.kotlinx:atomicfu", "0.29.0")),
            ),
        )
        write(
            "com/example/kmp-iosarm64/1.0.0-local/kmp-iosarm64-1.0.0-local.module",
            metadata(
                // Native targets list implementation dependencies as api and have no runtime variant.
                variant("kotlin-api", dependency("org.jetbrains.kotlinx:atomicfu", "0.29.0"), dependency("io.ktor:ktor-client-darwin", "3.6.1")),
                variant("kotlin-metadata", dependency("org.jetbrains.kotlinx:atomicfu", "0.29.0")),
            ),
        )
        val module = Module(repository.resolve("kmp"), ":kmp", "com.example", "kmp", Version(1, 0, 0))
        assertEquals(
            mapOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core" to Dependency("1.11.0", Scope.Api),
                "org.jetbrains.kotlinx:kotlinx-datetime" to Dependency("0.8.0", Scope.Api),
                "org.jetbrains.kotlinx:atomicfu" to Dependency("0.29.0", Scope.Implementation),
                "io.ktor:ktor-client-darwin" to Dependency("3.6.1", Scope.Api),
            ),
            publishedDependencies(repository, module, "1.0.0-local"),
        )
    }
}
