package com.lightningkite.release

import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenRepositoryTest {
    private val now = ZonedDateTime.parse("2026-11-10T12:00:00Z")

    @Test
    fun mergeKeepsEarlierVersionsAndNamesTheNewestPlainRelease() {
        val first = mergeMavenMetadata(null, "com.example", "a", "8.4.2", now)
        val second = mergeMavenMetadata(first, "com.example", "a", "1000.0.0", now)
        val third = mergeMavenMetadata(second, "com.example", "a", "1000.0.1-local", now)
        assertEquals(listOf("8.4.2", "1000.0.0", "1000.0.1-local"), mavenMetadataVersions(third))
        assertTrue("<release>1000.0.0</release>" in third)
        assertTrue("<lastUpdated>20261110120000</lastUpdated>" in third)
    }

    @Test
    fun stagedUploadMergesMetadataAndWritesChecksums() {
        val bucket = Files.createTempDirectory("bucket").toFile()
        val repository = DirectoryMavenRepository(bucket)
        fun stage(version: String): File = Files.createTempDirectory("staging").toFile().also { staging ->
            staging.resolve("com/example/a/$version").mkdirs()
            staging.resolve("com/example/a/$version/a-$version.jar").writeText("jar $version")
            staging.resolve("com/example/a/maven-metadata.xml").writeText(mergeMavenMetadata(null, "com.example", "a", version, now))
            staging.resolve("com/example/a/maven-metadata.xml.sha1").writeText("stale")
        }

        repository.uploadStaged(stage("1000.0.0"))
        repository.uploadStaged(stage("1000.0.1"))

        assertEquals("jar 1000.0.0", bucket.resolve("com/example/a/1000.0.0/a-1000.0.0.jar").readText())
        val metadata = bucket.resolve("com/example/a/maven-metadata.xml").readText()
        assertEquals(listOf("1000.0.0", "1000.0.1"), mavenMetadataVersions(metadata))
        assertEquals(mavenChecksums(metadata).getValue("sha1"), bucket.resolve("com/example/a/maven-metadata.xml.sha1").readText())
        assertNull(repository.read("com/example/missing/maven-metadata.xml"))
    }
}
