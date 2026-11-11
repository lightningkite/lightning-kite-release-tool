package com.lightningkite.release

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ModulesTest {
    private val root: File = Files.createTempDirectory("modules").toFile()

    private fun write(path: String, text: String) = root.resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    fun discoversDirectoriesWithAVersionLine() {
        write("gradle.properties", "org.gradle.jvmargs=-Xmx4g\n")
        write("basis/gradle.properties", "group=com.example\nversion=1000.0.0\n")
        write("database-mongodb/gradle.properties", "group=com.example.db\nversion=1002.3.4-local\n")
        write("demo/gradle.properties", "kotlin.code.style=official\n")
        write("basis/build/gradle.properties", "group=x\nversion=1.0.0\n")

        val modules = discoverModules(root)

        assertEquals(listOf(":basis", ":database-mongodb"), modules.map { it.gradlePath })
        assertEquals(listOf("com.example:basis", "com.example.db:database-mongodb"), modules.map { it.coordinate })
        assertEquals(Version(1002, 3, 4), modules[1].written)
        assertEquals(":database-mongodb:jvmTest", modules[1].task("jvmTest"))
    }

    @Test
    fun rootProjectCanBeTheModule() {
        write("gradle.properties", "group=com.example\nversion=1000.0.0\n")
        val module = discoverModules(root).single()
        assertEquals(":", module.gradlePath)
        assertEquals(root.name, module.artifact)
        assertEquals(":publishToMavenLocal", module.task("publishToMavenLocal"))
    }

    @Test
    fun versionLineIsReplacedInPlace() = assertEquals(
        "group=com.example\nversion=1000.0.1-local\nother=1\n",
        withVersion("group=com.example\nversion=1000.0.0\nother=1\n", "1000.0.1-local"),
    )

    @Test
    fun versionOnTheLastLineKeepsItsLineBreak() =
        assertEquals("group=com.example\nversion=1000.0.1\n", withVersion("group=com.example\nversion=1000.0.0\n", "1000.0.1"))

    @Test
    fun inputsAreTheScriptThePropertiesAndNonTestSources() {
        write("m/gradle.properties", "group=com.example\nversion=1000.0.0\n")
        write("m/build.gradle.kts", "plugins {}\n")
        write("m/src/commonMain/kotlin/A.kt", "class A\n")
        write("m/src/commonTest/kotlin/ATest.kt", "class ATest\n")
        val module = root.resolve("m")
        val original = inputsHash(module)

        write("m/gradle.properties", "group=com.example\nversion=1000.0.1\n")
        write("m/src/commonTest/kotlin/ATest.kt", "class ATest2\n")
        write("m/src/jvmTest/kotlin/BTest.kt", "class BTest\n")
        write("m/README.md", "docs\n")
        write("m/nested/src/main/kotlin/N.kt", "class N\n")
        assertEquals(original, inputsHash(module), "version, tests, docs and nested modules aren't inputs")

        write("m/src/commonMain/kotlin/A.kt", "class A2\n")
        val sourceChanged = inputsHash(module)
        assertNotEquals(original, sourceChanged)

        write("m/build.gradle.kts", "plugins { }\n")
        val scriptChanged = inputsHash(module)
        assertNotEquals(sourceChanged, scriptChanged)

        write("m/gradle.properties", "group=com.example\nversion=1000.0.1\nkotlin.mpp.x=true\n")
        assertNotEquals(scriptChanged, inputsHash(module))
    }

    @Test
    fun buildLogicIsHashedSeparately() {
        write("settings.gradle.kts", "include(\":m\")\n")
        write("build.gradle.kts", "plugins {}\n")
        write("buildSrc/src/main/kotlin/convention.gradle.kts", "// a\n")
        val original = buildLogicHash(root)

        write("buildSrc/build/generated.txt", "x")
        assertEquals(original, buildLogicHash(root))

        write("buildSrc/src/main/kotlin/convention.gradle.kts", "// b\n")
        val conventionChanged = buildLogicHash(root)
        assertNotEquals(original, conventionChanged)

        write("build.gradle.kts", "plugins { }\n")
        assertNotEquals(conventionChanged, buildLogicHash(root))
    }
}
