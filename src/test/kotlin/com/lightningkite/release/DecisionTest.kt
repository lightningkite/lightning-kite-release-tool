package com.lightningkite.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecisionTest {
    private val last = Version(1002, 3, 4)
    private val record = ReleaseRecord(
        inputsHash = "same",
        buildLogicHash = "logic",
        gitCommit = "abc123",
        dependencies = mapOf(
            "com.lightningkite.services:basis" to Dependency("1000.2.0", Scope.Api),
            "com.lightningkite.services:database" to Dependency("1000.0.0", Scope.Implementation),
            "io.ktor:ktor-client-core" to Dependency("3.6.1", Scope.Implementation),
            "org.jetbrains:markdown" to Dependency("0.7.3", Scope.Api),
        ),
    )
    private val unmoved = record.dependencies.mapValues { it.value.version }

    private fun decide(
        written: Version = last,
        abi: AbiChange? = null,
        current: Map<Coordinate, String> = unmoved,
        rebuild: Boolean = false,
    ) = decide(written, Release(last, record), abi, { current[it] }, rebuild)

    @Test
    fun unchanged() = assertNull(decide())

    @Test
    fun neverReleasedGoesOutAsWritten() = assertEquals(
        Version(1000, 0, 0),
        decide(Version(1000, 0, 0), lastRelease = null, abi = null, { null }, rebuild = false)?.version,
    )

    @Test
    fun ownChangesBumpByWhatTheAbiDid() {
        assertEquals(Version(1002, 3, 5), decide(abi = AbiChange.None)?.version)
        assertEquals(Version(1002, 4, 0), decide(abi = AbiChange.Added)?.version)
        assertEquals(Version(1003, 0, 0), decide(abi = AbiChange.Broken)?.version)
    }

    @Test
    fun aMinorOrPatchOfADependencyRebuildsNothing() =
        assertNull(decide(current = unmoved + ("com.lightningkite.services:basis" to "1000.9.9") + ("io.ktor:ktor-client-core" to "3.9.0")))

    @Test
    fun anApiDependencyMajorIsAMajor() =
        assertEquals(Version(1003, 0, 0), decide(current = unmoved + ("com.lightningkite.services:basis" to "1001.0.0"))?.version)

    @Test
    fun anImplementationDependencyMajorIsAPatch() {
        assertEquals(Version(1002, 3, 5), decide(current = unmoved + ("com.lightningkite.services:database" to "1001.0.0"))?.version)
        assertEquals(Version(1002, 3, 5), decide(current = unmoved + ("io.ktor:ktor-client-core" to "4.0.0"))?.version)
    }

    @Test
    fun aZeroPointMinorOfAThirdPartyApiDependencyIsAMajor() =
        assertEquals(Version(1003, 0, 0), decide(current = unmoved + ("org.jetbrains:markdown" to "0.8.0"))?.version)

    @Test
    fun theLargestCauseWins() {
        val decision = decide(
            abi = AbiChange.Added,
            current = unmoved + ("com.lightningkite.services:database" to "1001.0.0") + ("com.lightningkite.services:basis" to "1001.0.0"),
        )!!
        assertEquals(Version(1003, 0, 0), decision.version)
        assertEquals(3, decision.causes.size)
    }

    @Test
    fun rebuildIsAPatch() = assertEquals(Version(1002, 3, 5), decide(rebuild = true)?.version)

    @Test
    fun aHandWrittenVersionIsReleasedAsWritten() {
        assertEquals(Version(1003, 0, 0), decide(written = Version(1003, 0, 0))?.version)
        assertEquals(Version(2000, 0, 0), decide(written = Version(2000, 0, 0), abi = AbiChange.Broken)?.version)
        // An interrupted release left a patch in the file; a break made afterwards still becomes a major.
        assertEquals(Version(1003, 0, 0), decide(written = Version(1002, 3, 5), abi = AbiChange.Broken)?.version)
    }

    @Test
    fun recordsRoundTrip() {
        assertEquals(record, ReleaseRecord.parse(record.render()))
        assertEquals(record.copy(gitCommit = null), ReleaseRecord.parse(record.copy(gitCommit = null).render()))
    }
}
