package com.lightningkite.release

import org.tomlj.Toml
import org.tomlj.TomlTable

/** A Gradle version catalog, as far as releasing cares: which coordinates and plugins it names, at which versions. */
data class Catalog(val libraries: Map<String, Library>, val plugins: Map<String, Plugin>) {
    /** A version is null when something else (a BOM) supplies it. */
    data class Library(val coordinate: Coordinate, val version: String?)
    data class Plugin(val id: String, val version: String?)

    /** Every versioned coordinate.  The same coordinate at two versions means the build's result depends on luck. */
    fun versions(): Map<Coordinate, String> = libraries.values.filter { it.version != null }.groupBy({ it.coordinate }, { it.version!! })
        .mapValues { (coordinate, versions) ->
            versions.distinct().singleOrNull() ?: throw IllegalStateException("$coordinate is listed at ${versions.distinct().joinToString(" and ")}")
        }

    fun toToml(): String = buildString {
        fun versionOf(alias: String, version: String?) =
            version ?: throw IllegalStateException("`$alias` has no version, so it can't go into a published catalog")
        appendLine("[libraries]")
        for ((alias, library) in libraries.toSortedMap()) {
            appendLine("""$alias = { module = "${library.coordinate}", version = "${versionOf(alias, library.version)}" }""")
        }
        appendLine()
        appendLine("[plugins]")
        for ((alias, plugin) in plugins.toSortedMap()) {
            appendLine("""$alias = { id = "${plugin.id}", version = "${versionOf(alias, plugin.version)}" }""")
        }
    }

    companion object {
        fun parse(toml: String): Catalog {
            val parsed = Toml.parse(toml)
            check(!parsed.hasErrors()) { "Invalid version catalog: ${parsed.errors().joinToString { it.toString() }}" }
            val versions = parsed.getTable("versions")

            fun version(entry: TomlTable): String? = when (val version: Any? = entry.toMap()["version"]) {
                null -> null
                is String -> version
                is TomlTable -> version.getString("ref")?.let { ref: String ->
                    when (val referenced: Any = versions?.toMap()?.get(ref) ?: throw IllegalStateException("Version `$ref` is not defined")) {
                        is String -> referenced
                        is TomlTable -> referenced.richVersion()
                        else -> throw IllegalStateException("Version `$ref` is neither text nor a table")
                    }
                } ?: version.richVersion()
                else -> throw IllegalStateException("Unreadable version `$version`")
            }

            val libraries = parsed.getTable("libraries")?.let { table: TomlTable ->
                table.keySet().associateWith { alias ->
                    when (val entry: Any? = table.toMap()[alias]) {
                        is String -> Catalog.Library(entry.substringBeforeLast(':'), entry.substringAfterLast(':'))
                        is TomlTable -> Catalog.Library(
                            coordinate = entry.getString("module") ?: "${entry.getString("group")}:${entry.getString("name")}",
                            version = version(entry),
                        )
                        else -> throw IllegalStateException("Unreadable library `$alias`")
                    }
                }
            }.orEmpty()
            val plugins = parsed.getTable("plugins")?.let { table: TomlTable ->
                table.keySet().associateWith { alias ->
                    when (val entry: Any? = table.toMap()[alias]) {
                        is String -> Catalog.Plugin(entry.substringBeforeLast(':'), entry.substringAfterLast(':'))
                        is TomlTable -> Catalog.Plugin(entry.getString("id")!!, version(entry))
                        else -> throw IllegalStateException("Unreadable plugin `$alias`")
                    }
                }
            }.orEmpty()
            return Catalog(libraries, plugins)
        }

        private fun TomlTable.richVersion(): String? = getString("strictly") ?: getString("require") ?: getString("prefer")
    }
}

/**
 * The super catalog: every stack repo's catalog in one, each library named `<last part of its group>-<artifact>`
 * (`services-basis`) and each plugin by the last part of its id.
 */
fun mergeCatalogs(catalogs: List<Catalog>): Catalog {
    fun <T> unique(entries: List<Pair<String, T>>): Map<String, T> = entries.groupBy({ it.first }, { it.second }).mapValues { (alias, values) ->
        values.distinct().singleOrNull() ?: throw IllegalStateException("Two different entries would be named `$alias`: ${values.distinct()}")
    }
    return Catalog(
        libraries = unique(catalogs.flatMap { it.libraries.values }.map { "${it.coordinate.substringBefore(':').substringAfterLast('.')}-${it.coordinate.substringAfter(':')}" to it }),
        plugins = unique(catalogs.flatMap { it.plugins.values }.map { it.id.substringAfterLast('.') to it }),
    )
}

const val catalogGroup = "com.lightningkite.stack"

/** An upstream catalog pin in a settings file: `from("com.lightningkite.stack:<repo>-catalog:<version>")`. */
private val catalogPin = Regex(""""com\.lightningkite\.stack:([\w-]+)-catalog:([^"]+)"""")

/** The upstream repos a settings file pins, with the pinned catalog versions. */
fun pins(settings: String): Map<String, String> = catalogPin.findAll(settings).associate { it.groupValues[1] to it.groupValues[2] }

/** [settings] with the pins of the repos in [targets] moved to the given catalog versions. */
fun withPins(settings: String, targets: Map<String, String>): String = catalogPin.replace(settings) { match ->
    val (repo, pinned) = match.destructured
    "\"$catalogGroup:$repo-catalog:${targets[repo] ?: pinned}\""
}

/**
 * How far a catalog moved between two sets of versions, by the rules a module's own version follows: an entry
 * removed or moved to a new major is a major, an entry added or moved to a new minor is a minor, anything else a
 * patch.  Null when nothing changed.
 */
fun catalogBump(previous: Map<Coordinate, String>, next: Map<Coordinate, String>): Bump? =
    (previous.keys + next.keys).mapNotNull { coordinate ->
        val before = previous[coordinate]
        val after = next[coordinate]
        when {
            before == after -> null
            after == null -> Bump.Major
            before == null -> Bump.Minor
            isMajorMove(before, after) -> Bump.Major
            Version.parseOrNull(before)?.minor != Version.parseOrNull(after)?.minor -> Bump.Minor
            else -> Bump.Patch
        }
    }.maxOrNull()

/**
 * The newest of a catalog's published versions.  Catalogs released before their versions followed the modules'
 * bump rules are named by timestamp (`2026.11.11.0851`); any epoch-semver version is newer than those.
 * Compared number by number, never as text: as text, `1000.9.0` would outrank `1000.10.0`.  Anything that isn't
 * dot-separated numbers is not a release and is ignored.
 */
fun latestCatalog(published: List<String>): String? {
    published.mapNotNull { Version.parseOrNull(it) }.maxOrNull()?.let { return it.toString() }
    val timestamps = published.mapNotNull { text -> text.split('.').map { it.toIntOrNull() }.takeIf { null !in it }?.let { text to it.filterNotNull() } }
    return timestamps.maxWithOrNull { (_, a), (_, b) ->
        a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
    }?.first
}

fun nextCatalogVersion(published: List<String>, bump: Bump): Version =
    published.mapNotNull { Version.parseOrNull(it) }.maxOrNull()?.bumped(bump) ?: Version.epoch(1)
