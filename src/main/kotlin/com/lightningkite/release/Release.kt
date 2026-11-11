package com.lightningkite.release

import java.io.File
import kotlin.time.Clock
import kotlin.time.TimeSource

/** A mistake by the person running the tool, or a failed build: reported as its message, without a stack trace. */
class UserError(message: String) : Exception(message)

/** A library repo.  [bucket] is where its releases go; only [inStack] repos are part of the super catalog and BOM. */
class Repo(val name: String, val directory: File, val bucket: String, val inStack: Boolean) {
    /** The files that pin upstream catalogs. */
    val settingsFiles: List<File> get() = listOf("settings.gradle.kts", "buildSrc/settings.gradle.kts").map { directory.resolve(it) }.filter { it.exists() }
}

class Options(
    /** A real release to the buckets, instead of `-local` builds in mavenLocal. */
    val public: Boolean,
    /** Print what a release would do; leave nothing edited or published. */
    val plan: Boolean = false,
    val tests: Boolean = public,
    /** Repo names, module artifacts, coordinates or Gradle paths to release although unchanged. */
    val rebuild: Set<String> = setOf(),
)

/** Where a run publishes, and what differs with it. */
private enum class Destination(val versionSuffix: String, val publishTasks: List<String>, val catalogTasks: List<String>) {
    MavenLocal("-local", listOf("publishToMavenLocal"), listOf(":publishCatalogPublicationToMavenLocal")),

    // Also into mavenLocal, so the machine that released can build against the release straight away.
    Buckets(
        "",
        listOf("publishAllPublicationsToStagingRepository", "publishToMavenLocal"),
        listOf(":publishCatalogPublicationToStagingRepository", ":publishCatalogPublicationToMavenLocal"),
    ),
}

/** A `-local` build of a module in mavenLocal. */
private class LocalBuild(val version: Version, val record: ReleaseRecord, val abi: String)

/** What is known about a module before anything is decided. */
private class ModuleState(val module: Module, val last: Release?, val inputsHash: String, val localBuild: LocalBuild?) {
    /** The bump of a module whose own inputs changed depends on its ABI, which only a build can tell. */
    val ownInputsChanged: Boolean get() = last != null && last.record.inputsHash != inputsHash

    /** A local build of exactly these inputs already dumped the ABI. */
    val locallyDumpedAbi: String? get() = localBuild?.takeIf { it.record.inputsHash == inputsHash }?.abi
}

/** What a run does with a module.  [decision] is null when it is unchanged since its last public release. */
private class Planned(val state: ModuleState, val decision: Decision?, val publish: Boolean) {
    val module: Module get() = state.module

    /** The version everything else should now use. */
    val version: Version get() = decision?.version ?: checkNotNull(state.last).version

    /** [version] as it is written into files and published. */
    fun published(destination: Destination): String = if (decision == null) version.toString() else "$version${destination.versionSuffix}"
}

private class RepoPlan(val repo: Repo, val modules: List<Planned>, val buildLogicHash: String, val localCatalogStale: Boolean) {
    val toPublish: List<Planned> get() = modules.filter { it.publish }
    val rootIsModule: Boolean get() = modules.any { it.module.gradlePath == ":" }
}

/** What a run learns as it walks the repos, so a later repo sees what the earlier ones just released. */
private class Run(val options: Options, val owner: Map<Coordinate, String>, val buckets: Map<String, MavenRepository>) {
    val destination = if (options.public) Destination.Buckets else Destination.MavenLocal
    val current = mutableMapOf<Coordinate, Version>()
    val records = mutableMapOf<Coordinate, ReleaseRecord>()
    val publicCatalogs = mutableMapOf<String, List<String>>()
    val reposWithLocalBuilds = mutableSetOf<String>()
    val committed = mutableListOf<String>()
}

private fun catalogDirectory(repo: String) = "${catalogGroup.replace('.', '/')}/$repo-catalog"
private fun catalogFile(repo: String, version: String) = "${catalogDirectory(repo)}/$version/$repo-catalog-$version.toml"
private const val stackCatalogDirectory = "com/lightningkite/stack-catalog"
private fun stackCatalogFile(version: String) = "$stackCatalogDirectory/$version/stack-catalog-$version.toml"
private const val stackBom: Coordinate = "com.lightningkite:stack-bom"

class Releaser(
    private val repos: List<Repo>,
    private val mavenLocal: File,
    /** Opens a bucket; for writing when the run is a real release. */
    private val openRepository: (bucket: String, forWriting: Boolean) -> MavenRepository,
    private val gradle: Gradle,
    /** The Gradle project that publishes the super catalog and BOM. */
    private val stackProject: File,
    private val journal: Journal,
    private val out: (String) -> Unit = ::println,
) {
    fun release(options: Options) {
        journal.restore()
        val converted = repos.map { it to discoverModules(it.directory) }.filter { (_, modules) -> modules.isNotEmpty() }
        val unknown = options.rebuild.filter { name -> converted.none { (repo, modules) -> repo.name == name || modules.any { it.isNamed(name) } } }
        if (unknown.isNotEmpty()) throw UserError("--rebuild: there is no repo or module named ${unknown.joinToString()}")
        val releasing = options.public && !options.plan
        if (releasing) for ((repo, _) in converted) {
            if (exec(repo.directory, "git", "status", "--porcelain").isNotEmpty()) throw UserError("${repo.name}: commit or stash your changes before a public release")
        }

        // Opened together and first, so a release that can't write to a later repo's bucket fails before it starts.
        val buckets = repos.map { it.bucket }.distinct().associateWith { openRepository(it, releasing) }
        try {
            val run = Run(options, owner = converted.flatMap { (repo, modules) -> modules.map { it.coordinate to repo.name } }.toMap(), buckets)
            for ((repo, modules) in converted) {
                try {
                    val plan = plan(repo, modules, run)
                    print(plan, run)
                    for (planned in plan.modules) {
                        run.current[planned.module.coordinate] = planned.version
                        planned.state.last?.let { run.records[planned.module.coordinate] = it.record }
                    }
                    if (!options.public && plan.modules.any { it.decision != null }) run.reposWithLocalBuilds += repo.name
                    if (!options.plan && (plan.toPublish.isNotEmpty() || plan.localCatalogStale)) execute(plan, run)
                } finally {
                    // Puts back every version line and pin this repo's step edited, unless a release committed them.
                    journal.restore()
                }
            }
            if (!options.plan) publishStack(run)
            if (run.committed.isNotEmpty()) out("Release commits to push: ${run.committed.joinToString()}")
        } finally {
            buckets.values.forEach { it.close() }
        }
    }

    /** Decides what happens to each module of a repo.  Edits pins (journaled) and may run Gradle for ABI dumps. */
    private fun plan(repo: Repo, modules: List<Module>, run: Run): RepoPlan {
        val options = run.options
        val bucket = run.buckets.getValue(repo.bucket)
        val last = lastReleases(bucket, modules)
        val states = modules.map { module ->
            val lastRelease = last[module.coordinate]
            ModuleState(module, lastRelease, inputsHash(module.directory), if (options.public) null else localBuild(module, newerThan = lastRelease?.version))
        }
        val thirdParty = repo.directory.resolve("gradle/libs.versions.toml").takeIf { it.exists() }?.let { Catalog.parse(it.readText()).versions() }.orEmpty()
        run.publicCatalogs[repo.name] = bucket.read("${catalogDirectory(repo.name)}/maven-metadata.xml")?.let { mavenMetadataVersions(it) }.orEmpty()

        // A module recorded against an older major of another repo's module must be rebuilt against the new one,
        // which takes that repo's newer catalog.  Moved before anything is built, the ABI dump included, so
        // source already adapted to the new major compiles.
        val upstreamsWithNewMajors = states.flatMap { state ->
            state.last?.record?.dependencies.orEmpty()
                .filter { (dependency, recorded) -> run.current[dependency]?.let { isMajorMove(recorded.version, it.toString()) } == true }
                .mapNotNull { (dependency, _) -> run.owner[dependency].takeIf { it != repo.name } }
        }.toSet()
        for (settings in repo.settingsFiles) {
            val text = settings.readText()
            journal.edit(settings, withPins(text, pins(text).mapValues { (upstream, pinned) ->
                val published = epochCatalogs(run.publicCatalogs[upstream].orEmpty())
                val latest = published.maxOrNull()?.toString()
                when {
                    !options.public && upstream in run.reposWithLocalBuilds -> "local"
                    latest != null && (Version.parseOrNull(pinned) !in published || upstream in upstreamsWithNewMajors) -> latest
                    else -> pinned
                }
            }))
        }

        val toDump = states.filter { it.ownInputsChanged && it.locallyDumpedAbi == null }
        if (toDump.isNotEmpty()) {
            out("${repo.name}: dumping the ABI of ${toDump.joinToString { it.module.artifact }}")
            gradle.run(repo.directory, toDump.map { it.module.task(abiDumpTask) })
        }
        fun abiPath(state: ModuleState) = "${state.module.mavenPath(checkNotNull(state.last).version.toString())}-abi.txt"
        val previousAbi = bucket.readAll(states.filter { it.ownInputsChanged }.map { abiPath(it) })

        fun rebuildRequested(module: Module) = options.rebuild.any { it == repo.name || module.isNamed(it) }
        val siblings = states.associateBy { it.module.coordinate }
        val decisions = mutableMapOf<Coordinate, Decision?>()
        fun currentVersion(dependency: Coordinate): String? {
            val sibling = siblings[dependency] ?: return run.current[dependency]?.toString() ?: thirdParty[dependency]
            if (dependency !in decisions) decisions[dependency] = decide(
                written = sibling.module.written,
                lastRelease = sibling.last,
                inputsHash = sibling.inputsHash,
                abi = {
                    val previous = checkNotNull(previousAbi.getValue(abiPath(sibling))) { "${abiPath(sibling)} is missing" }
                    abiChange(previous, sibling.locallyDumpedAbi ?: readAbiDump(sibling.module))
                },
                // Recursing here is what cascades a major through sibling modules in dependency order.
                currentVersion = ::currentVersion,
                rebuild = rebuildRequested(sibling.module),
            )
            return (decisions[dependency]?.version ?: checkNotNull(sibling.last).version).toString()
        }
        states.forEach { currentVersion(it.module.coordinate) }

        val planned = states.map { state ->
            val decision = decisions[state.module.coordinate]
            val local = state.localBuild
            // In local mode, a build of these inputs at this version, against these dependency majors, may already be there.
            val alreadyInMavenLocal = decision != null && local != null && local.version == decision.version &&
                local.record.inputsHash == state.inputsHash && dependencyCauses(local.record, ::currentVersion).isEmpty()
            Planned(state, decision, publish = decision != null && (!alreadyInMavenLocal || rebuildRequested(state.module)))
        }

        // The repo's local catalog is otherwise republished only along with a module, so it can still name a
        // local build that a public release has since overtaken.
        val localCatalogStale = !options.public && planned.any { it.decision != null } &&
            mavenLocal.resolve(catalogFile(repo.name, "local")).takeIf { it.exists() }?.let { Catalog.parse(it.readText()).versions() }.orEmpty()
                .let { listed -> planned.any { listed[it.module.coordinate] != it.published(run.destination) } }
        val rootIsModule = modules.any { it.gradlePath == ":" }
        return RepoPlan(repo, planned, buildLogicHash(repo.directory, rootIsModule), localCatalogStale)
    }

    private fun print(plan: RepoPlan, run: Run) {
        for (planned in plan.modules) {
            val coordinate = planned.module.coordinate
            val last = planned.state.last
            val decision = planned.decision
            out(
                when {
                    decision == null -> "  $coordinate ${planned.version} unchanged"
                    !planned.publish -> "  $coordinate ${planned.published(run.destination)} already in mavenLocal"
                    else -> "  $coordinate ${last?.version ?: "(new)"} → ${planned.published(run.destination)}: ${decision.causes.joinToString("; ")}"
                }
            )
            if (decision == null && last?.record?.buildLogicHash != plan.buildLogicHash) {
                out("    built with older build logic; `--rebuild ${planned.module.artifact}` releases it anyway")
            }
        }
    }

    /** Builds and publishes what [plan] decided. */
    private fun execute(plan: RepoPlan, run: Run) {
        val repo = plan.repo
        val public = run.options.public
        val destination = run.destination
        val toPublish = plan.toPublish

        // Every decided version, not only the ones being built: the catalog and sibling dependencies name them all.
        for (planned in plan.modules) if (planned.decision != null) {
            journal.edit(planned.module.propertiesFile, withVersion(planned.module.propertiesFile.readText(), planned.published(destination)))
        }
        val catalogVersion = if (public) {
            val bump = catalogBump(
                previous = plan.modules.mapNotNull { planned -> planned.state.last?.let { planned.module.coordinate to it.version.toString() } }.toMap(),
                next = plan.modules.associate { it.module.coordinate to it.version.toString() },
            )
            nextCatalogVersion(run.publicCatalogs.getValue(repo.name), checkNotNull(bump) { "${repo.name} releases modules, yet none of its versions moved" }).toString()
        } else "local"

        val staging = repo.directory.resolve("build/staging-repo").apply { deleteRecursively() }
        gradle.run(
            repo.directory,
            (if (run.options.tests) toPublish.flatMap { it.module.testTasks() } else listOf()) +
                toPublish.flatMap { planned -> destination.publishTasks.map { planned.module.task(it) } } +
                toPublish.map { it.module.task(abiDumpTask) } +
                destination.catalogTasks +
                "-PcatalogVersion=$catalogVersion",
        )

        // Hashed again because this release may have moved pins, which are part of the build logic.
        val releasedLogicHash = buildLogicHash(repo.directory, plan.rootIsModule)
        val records = toPublish.associateWith { planned ->
            ReleaseRecord(
                planned.state.inputsHash, releasedLogicHash, gitCommit = null,
                publishedDependencies(if (public) staging else mavenLocal, planned.module, planned.published(destination)),
            )
        }
        fun recordFiles(gitCommit: String?) = records.flatMap { (planned, record) ->
            val path = planned.module.mavenPath(planned.published(destination))
            listOf("$path-release.properties" to record.copy(gitCommit = gitCommit).render(), "$path-abi.txt" to readAbiDump(planned.module))
        }.toMap()
        val released = toPublish.map { "${it.module.artifact} ${it.published(destination)}" }

        if (public) {
            // The very first release goes out at the versions already written, so it may edit nothing.
            if (journal.edited.isNotEmpty()) {
                exec(repo.directory, "git", "add", *journal.edited.map { it.path }.toTypedArray())
                exec(repo.directory, "git", "commit", "-m", if (released.size <= 3) "Release ${released.joinToString()}" else "Release ${released.size} modules\n\n${released.joinToString("\n")}")
                journal.keep()
                run.committed += repo.name
            }
            val commit = exec(repo.directory, "git", "rev-parse", "HEAD")
            out("${repo.name}: uploading ${released.size} modules")
            val bucket = run.buckets.getValue(repo.bucket)
            bucket.uploadStaged(staging)
            // Last, so a record only exists for a release whose files are all uploaded.
            bucket.put(recordFiles(commit))
            run.publicCatalogs[repo.name] = run.publicCatalogs.getValue(repo.name) + catalogVersion
            out("${repo.name}: released ${released.joinToString()}, and ${repo.name}-catalog $catalogVersion")
        } else {
            DirectoryMavenRepository(mavenLocal).put(recordFiles(gitCommit = null))
            out("${repo.name}: published to mavenLocal ${released.joinToString().ifEmpty { "its catalog" }}")
        }
        for ((planned, record) in records) run.records[planned.module.coordinate] = record
    }

    /** Each module's newest release that has a release record, read in as few round trips as possible. */
    private fun lastReleases(bucket: MavenRepository, modules: List<Module>): Map<Coordinate, Release> {
        val metadata = bucket.readAll(modules.map { "${it.mavenDirectory}/maven-metadata.xml" })
        // Versions below epoch 1 predate this tool and have no records.
        val candidates = modules.associateWith { module ->
            metadata.getValue("${module.mavenDirectory}/maven-metadata.xml")?.let { mavenMetadataVersions(it) }.orEmpty()
                .mapNotNull { Version.parseOrNull(it) }.filter { it >= Version.epoch(1) }.sortedDescending().toMutableList()
        }
        val found = mutableMapOf<Coordinate, Release>()
        // A version listed without a record is an upload that failed part-way; the release before it still counts.
        while (true) {
            val asking = candidates.filter { (module, versions) -> module.coordinate !in found && versions.isNotEmpty() }
                .map { (module, versions) -> module to versions.removeFirst() }
            if (asking.isEmpty()) return found
            fun recordPath(module: Module, version: Version) = "${module.mavenPath(version.toString())}-release.properties"
            val answers = bucket.readAll(asking.map { (module, version) -> recordPath(module, version) })
            for ((module, version) in asking) answers.getValue(recordPath(module, version))?.let {
                found[module.coordinate] = Release(version, ReleaseRecord.parse(it))
            }
        }
    }

    /** The newest `-local` build of [module] in mavenLocal that is still ahead of the public release. */
    private fun localBuild(module: Module, newerThan: Version?): LocalBuild? {
        val version = mavenLocal.resolve(module.mavenDirectory).listFiles().orEmpty().filter { it.name.endsWith("-local") }
            .mapNotNull { Version.parseOrNull(it.name.removeSuffix("-local")) }
            .filter { newerThan == null || it > newerThan }.maxOrNull() ?: return null
        val path = module.mavenPath("$version-local")
        val record = mavenLocal.resolve("$path-release.properties").takeIf { it.exists() } ?: return null
        return LocalBuild(version, ReleaseRecord.parse(record.readText()), mavenLocal.resolve("$path-abi.txt").readText())
    }

    /**
     * Publishes `com.lightningkite:stack-catalog` and `stack-bom` from the stack repos' latest catalogs, unless
     * nothing in them moved.  Publicly, only when no module still depends on an older major of another: apps must
     * never see a half-finished cascade.
     */
    private fun publishStack(run: Run) {
        val public = run.options.public
        val stackRepos = repos.filter { it.inStack }
        val bucket = run.buckets.getValue(stackRepos.map { it.bucket }.distinct().single())

        val merged = mergeCatalogs(stackRepos.map { repo ->
            val toml = if (!public && repo.name in run.reposWithLocalBuilds) {
                mavenLocal.resolve(catalogFile(repo.name, "local")).readText()
            } else {
                val version = latestCatalog(run.publicCatalogs[repo.name].orEmpty())
                    ?: return out("stack: ${repo.name} has no released catalog yet, so no stack catalog")
                checkNotNull(bucket.read(catalogFile(repo.name, version))) { "${catalogFile(repo.name, version)} is missing" }
            }
            Catalog.parse(toml)
        })
        if (public) {
            val stale = run.records.filterKeys { coordinate -> stackRepos.any { it.name == run.owner[coordinate] } }
                .filterValues { record -> dependencyCauses(record) { run.current[it]?.toString() }.isNotEmpty() }
            if (stale.isNotEmpty()) return out("stack: not published; still built against older majors: ${stale.keys.joinToString()}")
        }

        val published = if (public) bucket.read("$stackCatalogDirectory/maven-metadata.xml")?.let { mavenMetadataVersions(it) }.orEmpty() else listOf()
        val previous = if (public) latestCatalog(published)?.let { bucket.read(stackCatalogFile(it)) }
        else mavenLocal.resolve(stackCatalogFile("local")).takeIf { it.exists() }?.readText()
        // The stack catalog's version follows what moved in it, by the same rules as a module's.
        val bump = catalogBump(previous = previous?.let { Catalog.parse(it).versions() - stackBom }.orEmpty(), next = merged.versions())
            ?: return out("stack: unchanged")
        val version = if (public) nextCatalogVersion(published, bump).toString() else "local"

        val build = stackProject.resolve("build").apply { mkdirs() }
        // The catalog names its own BOM, so an app has one version to change.
        build.resolve("stack.versions.toml").writeText(merged.copy(libraries = merged.libraries + ("stack-bom" to Catalog.Library(stackBom, version))).toToml())
        build.resolve("stack-bom.txt").writeText(merged.versions().entries.joinToString("\n") { (coordinate, moduleVersion) -> "$coordinate:$moduleVersion" })
        val staging = build.resolve("staging-repo").apply { deleteRecursively() }
        gradle.run(stackProject, run.destination.publishTasks + "-PstackVersion=$version")
        if (public) bucket.uploadStaged(staging)
        out("stack: published stack-catalog and stack-bom $version")
    }
}

/** Runs a command, returning what it printed; throws when it fails. */
fun exec(directory: File, vararg command: String): String {
    val process = ProcessBuilder(*command).directory(directory).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0) { "`${command.joinToString(" ")}` failed in $directory:\n$output" }
    return output
}
