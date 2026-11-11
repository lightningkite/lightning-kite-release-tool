package com.lightningkite.release

/**
 * Epoch semver: `(epoch × 1000 + breaks).minor.patch`.  `7003.2.1` is design era 7, third breaking change,
 * second addition, first fix.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString(): String = "$major.$minor.$patch"

    fun bumped(bump: Bump): Version = when (bump) {
        Bump.Major -> Version(major + 1, 0, 0)
        Bump.Minor -> Version(major, minor + 1, 0)
        Bump.Patch -> Version(major, minor, patch + 1)
    }

    companion object {
        /** Null for anything that isn't a plain `major.minor.patch`, such as prereleases and `-local` builds. */
        fun parseOrNull(text: String): Version? =
            Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(text.trim())?.destructured?.let { (major, minor, patch) ->
                Version(major.toInt(), minor.toInt(), patch.toInt())
            }

        fun parse(text: String): Version = parseOrNull(text) ?: throw IllegalArgumentException("`$text` is not a major.minor.patch version")

        fun epoch(epoch: Int): Version {
            require(epoch >= 1) { "Epochs start at 1, got $epoch" }
            return Version(epoch * 1000, 0, 0)
        }
    }
}

/** Ordered: a larger bump wins. */
enum class Bump { Patch, Minor, Major }

/**
 * The version to release.  [written] (the module's `gradle.properties`) is a floor, not an override, so a version
 * left in the file by an interrupted release can't hide a break made afterwards.  A module never released before
 * goes out at the written version.
 */
fun nextVersion(lastRelease: Version?, written: Version, bump: Bump): Version =
    if (lastRelease == null) written else maxOf(written, lastRelease.bumped(bump))

/**
 * Whether going from [recorded] to [current] of some dependency is a breaking move: a different major, or a
 * different minor of a `0.x` library.  Versions that don't start with numbers break whenever they differ.
 */
fun isMajorMove(recorded: String, current: String): Boolean {
    if (recorded == current) return false
    fun leading(version: String) = Regex("""^(\d+)(?:\.(\d+))?""").find(version)?.destructured?.let { (major, minor) -> major.toInt() to minor.toIntOrNull() }
    val (recordedMajor, recordedMinor) = leading(recorded) ?: return true
    val (currentMajor, currentMinor) = leading(current) ?: return true
    if (recordedMajor != currentMajor) return true
    return recordedMajor == 0 && recordedMinor != currentMinor
}

enum class AbiChange { None, Added, Broken }

/** Lines removed or changed break code compiled against them; lines only added don't. */
fun abiChange(previous: String, current: String): AbiChange {
    val previousLines = previous.lines().filter { it.isNotBlank() }.toSet()
    val currentLines = current.lines().filter { it.isNotBlank() }.toSet()
    return when {
        !currentLines.containsAll(previousLines) -> AbiChange.Broken
        currentLines.size > previousLines.size -> AbiChange.Added
        else -> AbiChange.None
    }
}
