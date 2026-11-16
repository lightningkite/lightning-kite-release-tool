package com.lightningkite.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    private val last = Version(7003, 2, 1)

    @Test
    fun bumps() {
        assertEquals(Version(7004, 0, 0), last.bumped(Bump.Major))
        assertEquals(Version(7003, 3, 0), last.bumped(Bump.Minor))
        assertEquals(Version(7003, 2, 2), last.bumped(Bump.Patch))
        assertEquals(Bump.Major, maxOf(Bump.Patch, Bump.Major, Bump.Minor))
    }

    @Test
    fun onlyPlainVersionsParse() {
        assertEquals(last, Version.parseOrNull("7003.2.1"))
        assertNull(Version.parseOrNull("7003.2.1-local"))
        assertNull(Version.parseOrNull("1.4.0-prerelease-134-0107ea13"))
        assertEquals(Version(2000, 0, 0), Version.epoch(2))
    }

    @Test
    fun majorMoves() {
        assertTrue(isMajorMove("1000.4.2", "1001.0.0"))
        assertFalse(isMajorMove("1000.4.2", "1000.5.0"))
        assertFalse(isMajorMove("1.11.0", "1.12.3"))
        assertTrue(isMajorMove("0.7.3", "0.8.0"), "a 0.x minor is a major")
        assertFalse(isMajorMove("0.7.3", "0.7.4"))
        assertFalse(isMajorMove("33.1.0-jre", "33.2.0-jre"))
        assertTrue(isMajorMove("release-a", "release-b"))
    }

    @Test
    fun abiChanges() {
        val abi = "class A {\n  fun a()\n}\n"
        assertEquals(AbiChange.None, abiChange(abi, abi + "\n", emptySet()))
        assertEquals(AbiChange.Added, abiChange(abi, "class A {\n  fun a()\n  fun b()\n}\n", emptySet()))
        assertEquals(AbiChange.Broken, abiChange(abi, "class A {\n  fun a(Int)\n}\n", emptySet()))
        assertEquals(AbiChange.Broken, abiChange(abi, "class A {\n}\n", emptySet()))
    }

    @Test
    fun aLineRemovedFromOneClassBreaksThoughAnotherHasIt() = assertEquals(
        AbiChange.Broken,
        abiChange("class A {\n  fun a()\n}\nclass B {\n  fun a()\n}\n", "class A {\n  fun a()\n}\nclass B {\n}\n", emptySet()),
    )

    private fun dump(jvmSsr: String, android: String) =
        "# android/library.api\nclass A {\n$android}\n\n# jvmSsr/library.api\nclass A {\n$jvmSsr}\n\n# library.klib.api\nfinal fun k()\n"

    @Test
    fun aLineRemovedFromOneTargetBreaksThoughAnotherHasIt() = assertEquals(
        AbiChange.Broken,
        abiChange(dump(jvmSsr = "  fun a()\n", android = "  fun a()\n"), dump(jvmSsr = "", android = "  fun a()\n"), emptySet()),
    )

    @Test
    fun experimentalTargetsAreNotCompared() {
        val both = dump(jvmSsr = "  fun a()\n", android = "  fun a()\n")
        assertEquals(AbiChange.None, abiChange(both, dump(jvmSsr = "", android = "  fun a()\n"), setOf("jvmSsr")))
        assertEquals(AbiChange.None, abiChange(both, dump(jvmSsr = "  fun a()\n  fun b()\n", android = "  fun a()\n"), setOf("jvmSsr")))
        assertEquals(AbiChange.Broken, abiChange(both, dump(jvmSsr = "  fun a()\n", android = ""), setOf("jvmSsr")))
    }

    @Test
    fun starLeavesOutTheWholeModule() =
        assertEquals(AbiChange.None, abiChange("# library-compose.api\nclass A {\n  fun a()\n}\n", "# library-compose.api\nclass A {\n}\n", setOf("*")))

    @Test
    fun anExperimentalTargetWithoutItsOwnDumpIsRefused() {
        val both = dump(jvmSsr = "  fun a()\n", android = "  fun a()\n")
        assertFailsWith<UserError> { abiChange(both, both, setOf("jvmSrr")) }
        assertFailsWith<UserError> { abiChange(both, both, setOf("js")) }
    }
}
