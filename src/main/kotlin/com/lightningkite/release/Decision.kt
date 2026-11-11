package com.lightningkite.release

enum class Scope { Api, Implementation }

data class Dependency(val version: String, val scope: Scope)

/** What a release was made from, stored beside its artifacts as `<artifact>-<version>-release.properties`. */
data class ReleaseRecord(
    val inputsHash: String,
    val buildLogicHash: String,
    val gitCommit: String?,
    val dependencies: Map<Coordinate, Dependency>,
) {
    fun render(): String = buildString {
        appendLine("inputsHash = $inputsHash")
        appendLine("buildLogicHash = $buildLogicHash")
        if (gitCommit != null) appendLine("gitCommit = $gitCommit")
        for ((coordinate, dependency) in dependencies.toSortedMap()) {
            appendLine("$coordinate = ${dependency.version}, ${dependency.scope.name.lowercase()}")
        }
    }

    companion object {
        fun parse(text: String): ReleaseRecord {
            val entries = text.lines().filter { it.isNotBlank() }.associate { it.substringBefore(" = ") to it.substringAfter(" = ") }
            return ReleaseRecord(
                inputsHash = entries.getValue("inputsHash"),
                buildLogicHash = entries.getValue("buildLogicHash"),
                gitCommit = entries["gitCommit"],
                // Coordinates are the only keys with a colon.
                dependencies = entries.filterKeys { ':' in it }.mapValues { (_, value) ->
                    Dependency(value.substringBefore(", "), Scope.entries.single { it.name.lowercase() == value.substringAfter(", ") })
                },
            )
        }
    }
}

/** A module's release: its version and what it was made from. */
class Release(val version: Version, val record: ReleaseRecord)

data class Cause(val bump: Bump, val text: String)

/** Why a module must be released again although its own files are as they were at [record]. */
fun dependencyCauses(record: ReleaseRecord, currentVersion: (Coordinate) -> String?): List<Cause> =
    record.dependencies.mapNotNull { (coordinate, dependency) ->
        val current = currentVersion(coordinate) ?: return@mapNotNull null
        if (!isMajorMove(dependency.version, current)) return@mapNotNull null
        when (dependency.scope) {
            Scope.Api -> Cause(Bump.Major, "api dependency $coordinate ${dependency.version} → $current")
            // Must be rebuilt against it, but its own users don't see it.
            Scope.Implementation -> Cause(Bump.Patch, "implementation dependency $coordinate ${dependency.version} → $current")
        }
    }

data class Decision(val version: Version, val causes: List<String>)

/**
 * The module's next release, or null when it is unchanged since [lastRelease].  [abi] is asked only when the
 * module's own inputs changed, because answering it means compiling.
 */
fun decide(
    written: Version,
    lastRelease: Release?,
    inputsHash: String,
    abi: () -> AbiChange,
    currentVersion: (Coordinate) -> String?,
    rebuild: Boolean,
): Decision? {
    if (lastRelease == null) return Decision(written, listOf("never released"))
    val last = lastRelease.version
    val record = lastRelease.record
    val causes = buildList {
        if (inputsHash != record.inputsHash) add(
            when (abi()) {
                AbiChange.Broken -> Cause(Bump.Major, "ABI lines removed or changed")
                AbiChange.Added -> Cause(Bump.Minor, "ABI lines added")
                AbiChange.None -> Cause(Bump.Patch, "inputs changed, ABI identical")
            }
        )
        addAll(dependencyCauses(record, currentVersion))
        if (rebuild) add(Cause(Bump.Patch, "--rebuild"))
    }
    val bump = causes.maxOfOrNull { it.bump }
    return when {
        bump != null -> Decision(nextVersion(last, written, bump), causes.map { it.text })
        // A person starting an epoch or declaring a source break, or a release that was committed but never uploaded.
        written > last -> Decision(written, listOf("$written is written but not released"))
        else -> null
    }
}
