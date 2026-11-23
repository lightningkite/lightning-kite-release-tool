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

/**
 * Lines removed or changed break code compiled against them; lines only added don't.  Dumps are as `readAbiDump`
 * joins them: each file under a `# <path>` header, whose first directory is its target (`jvmSsr/library.api`).
 * [experimentalTargets] are left out of both sides; `*` leaves out the whole module.
 */
fun abiDiff(previous: String, current: String, experimentalTargets: Set<String>): AbiDiff {
    val currentTargets = current.lines().filter { it.startsWith("# ") && '/' in it }.map { it.removePrefix("# ").substringBefore('/') }.toSet()
    val unknown = experimentalTargets - "*" - currentTargets
    if (unknown.isNotEmpty()) {
        val existing = currentTargets.joinToString().ifEmpty { "none" }
        throw UserError("lk.experimentalTargets=${experimentalTargets.joinToString(",")}: ${unknown.joinToString()} has no ABI dump of its own (targets with one: $existing)")
    }

    // A member line like `public fun toString ()Ljava/lang/String;` repeats across classes and targets, so a line
    // only counts together with its file and enclosing declaration.
    fun lines(dump: String): Set<AbiLine> = buildSet {
        var path = ""
        var declaration = ""
        for (line in dump.lines()) {
            when {
                line.startsWith("# ") -> { path = line.removePrefix("# "); declaration = "" }
                "*" in experimentalTargets || path.substringBefore('/', missingDelimiterValue = "") in experimentalTargets -> {}
                line.isBlank() -> {}
                line == "}" -> declaration = ""
                !line[0].isWhitespace() && line.endsWith("{") -> { declaration = line; add(AbiLine(path, line, member = null)) }
                else -> add(AbiLine(path, declaration, line))
            }
        }
    }
    val previousLines = lines(previous)
    val currentLines = lines(current)
    return AbiDiff(removed = previousLines - currentLines, added = currentLines - previousLines)
}

/** A line of an ABI dump with the file and declaration it is in.  A declaration's own line has no [member]. */
data class AbiLine(val path: String, val declaration: String, val member: String?)

class AbiDiff(val removed: Set<AbiLine>, val added: Set<AbiLine>) {
    val change: AbiChange get() = when {
        removed.isNotEmpty() -> AbiChange.Broken
        added.isNotEmpty() -> AbiChange.Added
        else -> AbiChange.None
    }

    /** The declarations touched, by simple name: `removed or changed in Foo, Bar; added in Baz`. */
    fun summary(): String {
        // `public final class com/a/Foo : …` on the JVM, `final class com.a/Foo : …` in a klib dump.
        fun name(declaration: String) = Regex("""\S*/\S*""").find(declaration)?.value?.substringAfterLast('/') ?: "top level"
        fun names(lines: Set<AbiLine>): String {
            val distinct = lines.map { name(it.declaration) }.distinct()
            val shown = distinct.take(5).joinToString()
            return if (distinct.size > 5) "$shown and ${distinct.size - 5} more" else shown
        }
        return listOfNotNull(
            removed.takeIf { it.isNotEmpty() }?.let { "removed or changed in ${names(it)}" },
            added.takeIf { it.isNotEmpty() }?.let { "added in ${names(it)}" },
        ).joinToString("; ")
    }

    /** Like a unified diff, grouped by dump file and declaration; a changed line shows as removed and added. */
    fun render(): String = buildString {
        val signed = removed.map { '-' to it } + added.map { '+' to it }
        for ((path, inFile) in signed.groupBy { it.second.path }.toSortedMap()) {
            appendLine("# $path")
            for ((declaration, inDeclaration) in inFile.groupBy { it.second.declaration }.toSortedMap()) {
                val own = inDeclaration.firstOrNull { it.second.member == null }?.first ?: ' '
                appendLine("$own ${declaration.ifEmpty { "(top level)" }}")
                for ((sign, line) in inDeclaration.filter { it.second.member != null }.sortedBy { it.second.member }) appendLine("$sign ${line.member}")
            }
            appendLine()
        }
    }
}
