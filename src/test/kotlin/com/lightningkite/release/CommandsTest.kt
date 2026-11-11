package com.lightningkite.release

import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandsTest {
    private val work: File = Files.createTempDirectory("commands").toFile()
    private val bucket = DirectoryMavenRepository(work.resolve("bucket"))

    private fun write(path: String, text: String) = work.resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    fun pinMovesEveryPinToItsLatestCatalog() {
        val now = ZonedDateTime.parse("2026-11-11T12:00:00Z")
        fun publishCatalog(repo: String, vararg versions: String) = bucket.put(
            mapOf("com/lightningkite/stack/$repo-catalog/maven-metadata.xml" to versions.fold(null as String?) { metadata, version ->
                mergeMavenMetadata(metadata, "com.lightningkite.stack", "$repo-catalog", version, now)
            }!!)
        )
        publishCatalog("up", "5.4.1", "1000.0.0", "1000.1.0")
        publishCatalog("side", "5.4.1")
        write("down/settings.gradle.kts", """
            create("up") { from("com.lightningkite.stack:up-catalog:1000.0.0") }
            create("side") { from("com.lightningkite.stack:side-catalog:unreleased") }
            create("never") { from("com.lightningkite.stack:never-catalog:unreleased") }
        """.trimIndent())
        val repos = listOf("up", "side", "never", "down").map { Repo(it, work.resolve(it), "s3://stack", inStack = true) }

        pinToLatest(repos.last(), repos) { _, _ -> bucket }

        assertEquals(
            mapOf("up" to "1000.1.0", "side" to "unreleased", "never" to "unreleased"),
            pins(work.resolve("down/settings.gradle.kts").readText()),
        )
    }

    @Test
    fun epochRewritesEveryModule() {
        write("repo/a/gradle.properties", "group=com.example\nversion=1004.2.1\n")
        write("repo/b/gradle.properties", "group=com.example\nversion=1000.0.0\n")
        write("repo/gradle.properties", "org.gradle.jvmargs=-Xmx4g\n")

        writeEpoch(Repo("repo", work.resolve("repo"), "s3://stack", inStack = true), 2)

        assertEquals("group=com.example\nversion=2000.0.0\n", work.resolve("repo/a/gradle.properties").readText())
        assertEquals("group=com.example\nversion=2000.0.0\n", work.resolve("repo/b/gradle.properties").readText())
        assertEquals("org.gradle.jvmargs=-Xmx4g\n", work.resolve("repo/gradle.properties").readText())
    }

    @Test
    fun docsGoUnderTheVersionWithARedirectBesideIt() {
        write("repo/basis/gradle.properties", "group=com.example.services\nversion=1002.1.0\n")
        write("repo/basis/build/dokka/html/index.html", "<html>docs</html>")
        write("repo/basis/build/dokka/html/styles/main.css", "body {}")
        val module = discoverModules(work.resolve("repo")).single()

        val address = uploadDocs(bucket, module)

        val published = work.resolve("bucket/com/example/services/basis")
        assertEquals("<html>docs</html>", published.resolve("1002.1.0/docs/index.html").readText())
        assertEquals("body {}", published.resolve("1002.1.0/docs/styles/main.css").readText())
        assertEquals("${bucket.url}/com/example/services/basis/1002.1.0/docs/index.html", address)
        assertTrue(address in published.resolve("docs/index.html").readText(), "the redirect points into this repository, whichever bucket it is")
    }
}
