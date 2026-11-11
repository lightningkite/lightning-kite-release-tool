package com.lightningkite.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogsTest {
    @Test
    fun readsEveryWayACatalogWritesAVersion() {
        val catalog = Catalog.parse(
            """
            [versions]
            ktor = "3.6.1"
            strict = { strictly = "2.0.0" }

            [libraries]
            ktor-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
            ktor-cio = { module = "io.ktor:ktor-client-cio", version = { ref = "ktor" } }
            jsoup = "org.jsoup:jsoup:1.22.2"
            basis = { group = "com.lightningkite.services", name = "basis", version = "1000.0.0" }
            pinned = { module = "com.example:pinned", version.ref = "strict" }
            firebase = { module = "com.google.firebase:firebase-messaging" }

            [plugins]
            ksp = { id = "com.google.devtools.ksp", version = "2.3.13" }
            vite = "dev.opensavvy.vite.kotlin:0.6.0"
            """.trimIndent()
        )
        assertEquals(
            mapOf(
                "io.ktor:ktor-client-core" to "3.6.1",
                "io.ktor:ktor-client-cio" to "3.6.1",
                "org.jsoup:jsoup" to "1.22.2",
                "com.lightningkite.services:basis" to "1000.0.0",
                "com.example:pinned" to "2.0.0",
            ),
            catalog.versions(),
        )
        assertEquals(Catalog.Plugin("com.google.devtools.ksp", "2.3.13"), catalog.plugins["ksp"])
        assertEquals(Catalog.Plugin("dev.opensavvy.vite.kotlin", "0.6.0"), catalog.plugins["vite"])
    }

    @Test
    fun oneCoordinateAtTwoVersionsIsAnError() {
        val catalog = Catalog.parse("[libraries]\na = \"com.example:a:1.0.0\"\nb = \"com.example:a:2.0.0\"\n")
        assertFailsWith<IllegalStateException> { catalog.versions() }
    }

    @Test
    fun mergedCatalogNamesLibrariesByGroupAndArtifact() {
        val services = Catalog(
            libraries = mapOf("basis" to Catalog.Library("com.lightningkite.services:basis", "1000.0.0")),
            plugins = mapOf(),
        )
        val kiteui = Catalog(
            libraries = mapOf("library" to Catalog.Library("com.lightningkite.kiteui:library", "1002.1.0")),
            plugins = mapOf("kiteui" to Catalog.Plugin("com.lightningkite.kiteui", "1002.1.0")),
        )
        val merged = mergeCatalogs(listOf(services, kiteui))
        assertEquals(setOf("services-basis", "kiteui-library"), merged.libraries.keys)
        assertEquals(setOf("kiteui"), merged.plugins.keys)
        assertEquals(merged, Catalog.parse(merged.toToml()))
    }

    @Test
    fun aLibraryWithoutAVersionCannotBePublished() {
        val catalog = Catalog(libraries = mapOf("firebase" to Catalog.Library("com.google.firebase:firebase-messaging", null)), plugins = mapOf())
        assertFailsWith<IllegalStateException> { catalog.toToml() }
    }

    @Test
    fun aCatalogMovesByTheLargestMoveInIt() {
        val before = mapOf("com.example:a" to "1000.2.3", "com.example:b" to "1000.0.0")
        assertEquals(null, catalogBump(before, before))
        assertEquals(Bump.Patch, catalogBump(before, before + ("com.example:a" to "1000.2.4")))
        assertEquals(Bump.Minor, catalogBump(before, before + ("com.example:a" to "1000.3.0")))
        assertEquals(Bump.Major, catalogBump(before, before + ("com.example:a" to "1001.0.0") + ("com.example:b" to "1000.0.1")))
        assertEquals(Bump.Minor, catalogBump(before, before + ("com.example:c" to "1000.0.0")), "an added module")
        assertEquals(Bump.Major, catalogBump(before, before - "com.example:b"), "a removed module")
    }

    @Test
    fun catalogVersionsStartAtEpochOneAndOutrankTheTimestampsBeforeThem() {
        val timestamps = listOf("2026.11.11.0851", "2026.11.11.0912")
        assertEquals(null, latestCatalog(listOf()))
        assertEquals("2026.11.11.0912", latestCatalog(timestamps))
        assertEquals(Version(1000, 0, 0), nextCatalogVersion(timestamps, Bump.Patch))
        assertEquals("1000.0.0", latestCatalog(timestamps + "1000.0.0"))
        assertEquals(Version(1000, 1, 0), nextCatalogVersion(timestamps + "1000.0.0" + "1000.0.1", Bump.Minor))
    }

    @Test
    fun catalogVersionsAreComparedAsNumbers() {
        assertEquals("1000.10.0", latestCatalog(listOf("1000.9.0", "1000.10.0", "999.99.99")))
        assertEquals(Version(1000, 10, 1), nextCatalogVersion(listOf("1000.9.0", "1000.10.0"), Bump.Patch))
        assertEquals("2026.11.11.0851", latestCatalog(listOf("2026.11.11.0851", "2026.9.30.2359", "unspecified", "local")))
        assertEquals(null, latestCatalog(listOf("unspecified")))
    }

    private val settings = """
        versionCatalogs {
            create("sa") { from("com.lightningkite.stack:service-abstractions-catalog:2026.11.10.0200") }
            create("ls") { from("com.lightningkite.stack:lightning-server-catalog:2026.11.10.0300") }
        }
    """.trimIndent()

    @Test
    fun readsPins() = assertEquals(mapOf("service-abstractions" to "2026.11.10.0200", "lightning-server" to "2026.11.10.0300"), pins(settings))

    @Test
    fun movesOnlyTheNamedPins() {
        val moved = withPins(settings, mapOf("lightning-server" to "local"))
        assertEquals(mapOf("service-abstractions" to "2026.11.10.0200", "lightning-server" to "local"), pins(moved))
        assertEquals(settings, withPins(moved, mapOf("lightning-server" to "2026.11.10.0300")))
    }
}
