package com.lightningkite.release

/** Added by Kotlin's `abiValidation()` to JVM and Multiplatform modules alike; writes `build/kotlin/abi`. */
private const val abiDumpTask = "internalDumpKotlinAbi"

/** The module's ABI dumps as [abiDumpTask] left them, concatenated in path order, each under a `# <path>` header. */
private fun readAbiDump(module: Module): String {
    val directory = module.directory.resolve("build/kotlin/abi")
    val dumps = directory.walkTopDown().filter { it.isFile && it.extension == "api" }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.toList()
    check(dumps.isNotEmpty()) { "${module.gradlePath} has no ABI dump in $directory; is `abiValidation()` on in its build script?" }
    return dumps.joinToString("") { "# ${it.relativeTo(directory).invariantSeparatorsPath}\n${it.readText()}\n" }
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
}

/**
 * One `lk release` or `lk plan`.  [public] is a real release to the buckets instead of `-local` builds in
 * mavenLocal.  [rebuild] holds repo names, module artifacts, coordinates or Gradle paths to release although
 * unchanged.  [only] holds the names of the repos to release, or is empty for all of them.  [dropTargets] lets a
 * public release go out without a target its last release had.  [planOnly] prints what a release would do and
 * leaves nothing edited or published.
 */
class ReleaseRun(
    private val workspace: Workspace,
    private val public: Boolean,
    private val tests: Boolean,
    private val rebuild: Set<String>,
    private val only: Set<String>,
    private val dropTargets: Boolean,
    private val planOnly: Boolean,
) {
    private val mavenLocal = workspace.mavenLocal
    private val journal = workspace.journal

    // What the run learns as it walks the repos, so a later repo sees what the earlier ones just released.
    private val buckets = mutableMapOf<String, MavenRepository>()
    private val current = mutableMapOf<Coordinate, Version>()
    private val owner = mutableMapOf<Coordinate, String>()
    private val releaseRecords = mutableMapOf<Coordinate, ReleaseRecord>()
    private val publicCatalogs = mutableMapOf<String, List<String>>()
    private val reposWithLocalBuilds = mutableSetOf<String>()
    private val rebuiltLocally = mutableSetOf<Coordinate>()
    private val committed = mutableListOf<String>()

    /** The module's version as it is written into files and published. */
    private fun published(planned: Planned): String = if (planned.decision == null || public) planned.version.toString() else "${planned.version}-local"

    fun release() {
        journal.restore()
        lap("restored the journal")
        val converted = workspace.repos.map { it to discoverModules(it.directory) }.filter { (_, modules) -> modules.isNotEmpty() }
        lap("found modules")
        val unknown = rebuild.filter { name -> converted.none { (repo, modules) -> repo.name == name || modules.any { it.isNamed(name) } } }
        if (unknown.isNotEmpty()) throw UserError("--rebuild: there is no repo or module named ${unknown.joinToString()}")
        val unknownOnly = only.filter { name -> converted.none { (repo, _) -> repo.name == name } }
        if (unknownOnly.isNotEmpty()) throw UserError("--only: there is no repo named ${unknownOnly.joinToString()}")
        val selected = converted.filter { (repo, _) -> only.isEmpty() || repo.name in only }
        // Repos after the last selected one can't affect it, so they aren't even planned.
        val walked = converted.take(converted.indexOf(selected.last()) + 1)
        val releasing = public && !planOnly
        if (releasing) for ((repo, _) in selected) {
            if (exec(repo.directory, "git", "status", "--porcelain").isNotEmpty()) throw UserError("${repo.name}: commit or stash your changes before a public release")
        }

        // Opened together and first, so a release that can't write to a later repo's bucket fails before it starts.
        val writtenBuckets = selected.map { (repo, _) -> repo.bucket } + (if (only.isEmpty()) listOf(workspace.stackBucket) else listOf())
        for (bucket in (workspace.repos.map { it.bucket } + workspace.stackBucket).distinct()) {
            buckets[bucket] = workspace.open(bucket, forWriting = releasing && bucket in writtenBuckets)
            lap("opened $bucket")
        }
        try {
            var anythingToRelease = false
            for ((repo, modules) in walked) {
                try {
                    val planned = plan(repo, modules)
                    if (planned.any { it.publish }) anythingToRelease = true
                    for (module in planned) {
                        current[module.module.coordinate] = module.version
                        owner[module.module.coordinate] = repo.name
                        module.state.last?.let { releaseRecords[module.module.coordinate] = it.record }
                    }
                    if (!public && planned.any { it.decision != null }) reposWithLocalBuilds += repo.name
                    if (!public) rebuiltLocally += planned.filter { it.publish }.map { it.module.coordinate }
                    val catalogMissing = public && latestCatalog(publicCatalogs.getValue(repo.name)) == null
                    if ((repo to modules) !in selected) {
                        // A selected repo may build on it, and would go out against something unreleased.
                        if (planned.any { it.publish } || catalogMissing) throw UserError("--only: ${repo.name} has changes to release first; add it to --only")
                        continue
                    }
                    if (!planOnly && (planned.any { it.publish } || localCatalogStale(repo, planned) || catalogMissing)) {
                        execute(repo, planned)
                        anythingToRelease = true
                    }
                } finally {
                    // Puts back every version line and pin this repo's step edited, unless a release committed them.
                    journal.restore()
                }
            }
            // Repos that weren't walked may be built against an older major of what this run released, which
            // publishStack can't see.
            if (only.isNotEmpty()) println("stack: not published with --only; a run without it publishes it")
            else if (!planOnly) publishStack()
            if (!anythingToRelease) println("Nothing to release.")
            if (committed.isNotEmpty()) println("Release commits to push: ${committed.joinToString()}")
        } finally {
            buckets.values.forEach { it.close() }
            lap("closed buckets")
        }
    }

    /** Decides what happens to each module of a repo and prints it.  Edits pins (journaled) and may run Gradle for ABI dumps. */
    private fun plan(repo: Repo, modules: List<Module>): List<Planned> {
        val bucket = buckets.getValue(repo.bucket)
        val last = lastReleases(bucket, modules)
        lap("${repo.name}: read the last releases of ${modules.size} modules")
        val hashes = modules.associateWith { inputsHash(it.directory) }
        lap("${repo.name}: hashed inputs")
        val states = modules.map { module ->
            val lastRelease = last[module.coordinate]
            ModuleState(module, lastRelease, hashes.getValue(module), if (public) null else localBuild(module, newerThan = lastRelease?.version))
        }
        val thirdParty = repo.directory.resolve("gradle/libs.versions.toml").takeIf { it.exists() }?.let { Catalog.parse(it.readText()).versions() }.orEmpty()
        publicCatalogs[repo.name] = bucket.read("${catalogDirectory(repo.name)}/maven-metadata.xml")?.let { mavenMetadataVersions(it) }.orEmpty()
        lap("${repo.name}: read local builds and catalog versions")

        // A module recorded against an older major of another repo's module must be rebuilt against the new one,
        // which takes that repo's newer catalog.  Moved before anything is built, the ABI dump included, so
        // source already adapted to the new major compiles.
        val upstreamsWithNewMajors = states.flatMap { state ->
            state.last?.record?.dependencies.orEmpty()
                .filter { (dependency, recorded) -> current[dependency]?.let { isMajorMove(recorded.version, it.toString()) } == true }
                .mapNotNull { (dependency, _) -> owner[dependency] }
        }.toSet()
        for (settings in repo.settingsFiles) {
            val text = settings.readText()
            journal.edit(settings, withPins(text, pins(text).mapValues { (upstream, pinned) ->
                val released = epochCatalogs(publicCatalogs[upstream].orEmpty())
                val latest = released.maxOrNull()?.toString()
                when {
                    !public && upstream in reposWithLocalBuilds -> "local"
                    latest != null && (Version.parseOrNull(pinned) !in released || upstream in upstreamsWithNewMajors) -> latest
                    else -> pinned
                }
            }))
        }

        val toDump = states.filter { it.ownInputsChanged && it.locallyDumpedAbi == null }
        if (toDump.isNotEmpty()) workspace.gradle(repo.directory, toDump.map { it.module.task(abiDumpTask) })
        val changed = states.filter { it.ownInputsChanged }
        fun abiPath(state: ModuleState) = "${state.module.mavenPath(checkNotNull(state.last).version.toString())}-abi.txt"
        val previousAbi = bucket.readAll(changed.map { abiPath(it) })
        val abiDiffs = changed.associate { state ->
            val previous = checkNotNull(previousAbi.getValue(abiPath(state))) { "${abiPath(state)} is missing" }
            state.module.coordinate to abiDiff(previous, state.locallyDumpedAbi ?: readAbiDump(state.module), state.module.experimentalTargets)
        }
        fun abiDiffFile(module: Module) = workspace.outputDirectory.resolve("abi/${module.group}/${module.artifact}.diff")
        for (state in changed) {
            val diff = abiDiffs.getValue(state.module.coordinate)
            if (diff.change != AbiChange.None) abiDiffFile(state.module).apply { parentFile.mkdirs() }.writeText(diff.render())
        }

        if (changed.isNotEmpty()) lap("${repo.name}: compared the ABI of ${changed.size} modules")
        fun rebuildRequested(module: Module) = rebuild.any { it == repo.name || module.isNamed(it) }
        val siblings = states.associateBy { it.module.coordinate }
        val decisions = mutableMapOf<Coordinate, Decision?>()
        fun currentVersion(dependency: Coordinate): String? {
            val sibling = siblings[dependency] ?: return current[dependency]?.toString() ?: thirdParty[dependency]
            if (dependency !in decisions) {
                // An earlier `--rebuild` left a local build of these very inputs.  It stays in use, or the next run
                // without the flag would point the catalog back at the public version.
                val keptLocalBuild = sibling.localBuild?.takeIf { it.record.inputsHash == sibling.inputsHash }
                decisions[dependency] = decide(
                    written = sibling.module.written,
                    lastRelease = sibling.last,
                    abi = abiDiffs[dependency]?.change,
                    // Recursing here is what cascades a major through sibling modules in dependency order.
                    currentVersion = ::currentVersion,
                    rebuild = rebuildRequested(sibling.module),
                ) ?: keptLocalBuild?.let { Decision(it.version, listOf("--rebuild in an earlier run")) }
            }
            return (decisions[dependency]?.version ?: checkNotNull(sibling.last).version).toString()
        }
        states.forEach { currentVersion(it.module.coordinate) }

        val publishing = mutableMapOf<Coordinate, Boolean>()
        fun publishes(state: ModuleState): Boolean = publishing[state.module.coordinate] ?: run {
            val decision = decisions[state.module.coordinate]
            val local = state.localBuild
            // In local mode, a build of these inputs at this version, against these dependency majors, may already be there.
            val alreadyInMavenLocal = decision != null && local != null && local.version == decision.version &&
                local.record.inputsHash == state.inputsHash && dependencyCauses(local.record, ::currentVersion).isEmpty()
            // `X-local` names whatever was built last, so a build against one this run replaces is stale.
            val builtAgainstReplaced = local != null && local.record.dependencies.any { (dependency, recorded) ->
                recorded.version.endsWith("-local") && (dependency in rebuiltLocally || siblings[dependency]?.let { publishes(it) } == true)
            }
            val publish = decision != null && (!alreadyInMavenLocal || builtAgainstReplaced || rebuildRequested(state.module))
            publishing[state.module.coordinate] = publish
            publish
        }
        val planned = states.map { Planned(it, decisions[it.module.coordinate], publish = publishes(it)) }

        for (module in planned) {
            val decision = module.decision ?: continue
            if (!module.publish) continue
            println("  ${module.module.coordinate} ${module.state.last?.version ?: "(new)"} → ${published(module)}: ${decision.causes.joinToString("; ")}")
            val diff = abiDiffs[module.module.coordinate]
            if (diff != null && diff.change != AbiChange.None) println("    ${diff.summary()}: ${abiDiffFile(module.module)}")
        }
        val buildLogicHash = buildLogicHash(repo.directory)
        val olderBuildLogic = planned.filter { it.decision == null && it.state.last?.record?.buildLogicHash != buildLogicHash }
        if (planOnly && olderBuildLogic.isNotEmpty()) {
            println("  ${repo.name}: built with older build logic: ${olderBuildLogic.joinToString { it.module.artifact }}; `--rebuild` releases them anyway")
        }
        return planned
    }

    /**
     * The repo's local catalog is otherwise republished only along with a module, so it can still name a local
     * build that a public release has since overtaken.
     */
    private fun localCatalogStale(repo: Repo, planned: List<Planned>): Boolean {
        if (public || planned.none { it.decision != null }) return false
        val catalog = mavenLocal.resolve(catalogFile(repo.name, "local"))
        val listed = if (catalog.exists()) Catalog.parse(catalog.readText()).versions() else mapOf()
        return planned.any { listed[it.module.coordinate] != published(it) }
    }

    /** Builds and publishes what [plan] decided. */
    private fun execute(repo: Repo, planned: List<Planned>) {
        val toPublish = planned.filter { it.publish }

        // Every decided version, not only the ones being built: the catalog and sibling dependencies name them all.
        for (module in planned) if (module.decision != null) {
            journal.edit(module.module.propertiesFile, withVersion(module.module.propertiesFile.readText(), published(module)))
        }
        val catalogs = publicCatalogs.getValue(repo.name)
        val catalogVersion = when {
            !public -> "local"
            // Also when no module moved: an older version of this tool released modules without their catalog.
            latestCatalog(catalogs) == null -> Version.epoch(1).toString()
            else -> {
                val bump = catalogBump(
                    previous = planned.mapNotNull { module -> module.state.last?.let { module.module.coordinate to it.version.toString() } }.toMap(),
                    next = planned.associate { it.module.coordinate to it.version.toString() },
                )
                nextCatalogVersion(catalogs, checkNotNull(bump) { "${repo.name} releases modules, yet none of its versions moved" }).toString()
            }
        }

        val staging = repo.directory.resolve("build/staging-repo").apply { deleteRecursively() }
        workspace.gradle(repo.directory, buildList {
            // What the rolling deploy before this tool ran: the `test` task of JVM modules.  Kotlin Multiplatform
            // modules have no task of that name, and their tests (browser, simulator) were never part of a release.
            if (tests) for (module in toPublish) if (module.module.directory.resolve("src/test").isDirectory) add(module.module.task("test"))
            for (module in toPublish) {
                add(module.module.task(if (public) "publishAllPublicationsToStagingRepository" else "publishToMavenLocal"))
                add(module.module.task(abiDumpTask))
            }
            add(if (public) ":publishCatalogPublicationToStagingRepository" else ":publishCatalogPublicationToMavenLocal")
            add("-PcatalogVersion=$catalogVersion")
        })

        if (public) {
            // A target that can't be built here (iOS off a Mac) is silently left out of the publication, and apps
            // that use it then can't resolve the module at all.
            if (!dropTargets) {
                val released = toPublish.filter { it.state.last != null }
                fun lastMetadata(module: Planned) = "${module.module.mavenPath(checkNotNull(module.state.last).version.toString())}.module"
                val before = buckets.getValue(repo.bucket).readAll(released.map { lastMetadata(it) })
                val lost = released.mapNotNull { module ->
                    val previous = checkNotNull(before.getValue(lastMetadata(module))) { "${lastMetadata(module)} is missing" }
                    val staged = staging.resolve("${module.module.mavenPath(published(module))}.module").readText()
                    val missing = publishedTargets(previous) - publishedTargets(staged)
                    if (missing.isEmpty()) null
                    else "  ${module.module.coordinate} ${published(module)} has no ${missing.sorted().joinToString()}, which ${checkNotNull(module.state.last).version} had"
                }
                if (lost.isNotEmpty()) throw UserError(
                    "${repo.name}: not released, because this build lacks targets the last release had:\n${lost.joinToString("\n")}\n" +
                        "Release from a machine that builds them (iOS needs a Mac).  If they were removed on purpose, add --dropTargets."
                )
            }
            // So the machine that released can build against it straight away.  Only after the check: builds here
            // resolve mavenLocal first, so a refused release left there would shadow the real one for good.
            workspace.gradle(repo.directory, toPublish.map { it.module.task("publishToMavenLocal") } + ":publishCatalogPublicationToMavenLocal" + "-PcatalogVersion=$catalogVersion")
        }

        // Hashed again because this release may have moved pins, which are part of the build logic.
        val buildLogicHash = buildLogicHash(repo.directory)
        val records = toPublish.associateWith { module ->
            ReleaseRecord(
                module.state.inputsHash, buildLogicHash, gitCommit = null,
                publishedDependencies(if (public) staging else mavenLocal, module.module, published(module)),
            )
        }
        fun recordFiles(gitCommit: String?) = records.flatMap { (module, record) ->
            val path = module.module.mavenPath(published(module))
            listOf("$path-release.properties" to record.copy(gitCommit = gitCommit).render(), "$path-abi.txt" to readAbiDump(module.module))
        }.toMap()
        val released = toPublish.map { "${it.module.artifact} ${published(it)}" }

        if (public) {
            // The very first release goes out at the versions already written, so it may edit nothing.
            if (journal.edited.isNotEmpty()) {
                exec(repo.directory, "git", "add", *journal.edited.map { it.path }.toTypedArray())
                exec(repo.directory, "git", "commit", "-m", if (released.size <= 3) "Release ${released.joinToString()}" else "Release ${released.size} modules\n\n${released.joinToString("\n")}")
                journal.keep()
                committed += repo.name
            }
            val commit = exec(repo.directory, "git", "rev-parse", "HEAD")
            println("${repo.name}: uploading ${released.size} modules")
            val bucket = buckets.getValue(repo.bucket)
            bucket.uploadStaged(staging)
            // Last, so a record only exists for a release whose files are all uploaded.
            bucket.put(recordFiles(commit))
            publicCatalogs[repo.name] = catalogs + catalogVersion
            println("${repo.name}: released ${released.joinToString().ifEmpty { "no modules" }}, and ${repo.name}-catalog $catalogVersion")
        } else {
            DirectoryMavenRepository(mavenLocal).put(recordFiles(gitCommit = null))
            // A higher `-local` can only be left from before a version reset.  It would shadow this build, since
            // [localBuild] takes the highest.
            for (module in toPublish) {
                for (directory in mavenLocal.resolve(module.module.mavenDirectory).listFiles().orEmpty()) {
                    if (!directory.name.endsWith("-local")) continue
                    val version = Version.parseOrNull(directory.name.removeSuffix("-local")) ?: continue
                    if (version > module.version) directory.deleteRecursively()
                }
            }
            println("${repo.name}: published to mavenLocal ${released.joinToString().ifEmpty { "its catalog" }}")
        }
        for ((module, record) in records) releaseRecords[module.module.coordinate] = record
        lap("${repo.name}: published")
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
     * Publishes `com.lightningkite:stack-catalog` and `stack-bom` from every repo's latest catalog, unless nothing in
     * them moved.  Publicly, only when no module still depends on an older major of another: apps must never see a
     * half-finished cascade.
     */
    private fun publishStack() {
        val bucket = buckets.getValue(workspace.stackBucket)

        val merged = mergeCatalogs(workspace.repos.map { repo ->
            val toml = if (!public && repo.name in reposWithLocalBuilds) {
                mavenLocal.resolve(catalogFile(repo.name, "local")).readText()
            } else {
                val version = latestCatalog(publicCatalogs[repo.name].orEmpty())
                    ?: return println("stack: ${repo.name} has no released catalog yet, so no stack catalog")
                val file = catalogFile(repo.name, version)
                checkNotNull(buckets.getValue(repo.bucket).read(file)) { "$file is missing" }
            }
            Catalog.parse(toml)
        })
        if (public) {
            val stale = releaseRecords.filterValues { record -> dependencyCauses(record) { current[it]?.toString() }.isNotEmpty() }
            if (stale.isNotEmpty()) return println("stack: not published; still built against older majors: ${stale.keys.joinToString()}")
        }

        val published = if (public) bucket.read("$stackCatalogDirectory/maven-metadata.xml")?.let { mavenMetadataVersions(it) }.orEmpty() else listOf()
        val previous = if (public) latestCatalog(published)?.let { bucket.read(stackCatalogFile(it)) }
        else mavenLocal.resolve(stackCatalogFile("local")).takeIf { it.exists() }?.readText()
        lap("stack: read the catalogs")
        // The stack catalog's version follows what moved in it, by the same rules as a module's.
        val bump = catalogBump(previous = previous?.let { Catalog.parse(it).versions() - stackBom }.orEmpty(), next = merged.versions())
            ?: return
        val version = if (public) nextCatalogVersion(published, bump).toString() else "local"

        val build = workspace.stackProject.resolve("build").apply { mkdirs() }
        // The catalog names its own BOM, so an app has one version to change.
        build.resolve("stack.versions.toml").writeText(merged.copy(libraries = merged.libraries + ("stack-bom" to Catalog.Library(stackBom, version))).toToml())
        build.resolve("stack-bom.txt").writeText(merged.versions().entries.joinToString("\n") { (coordinate, moduleVersion) -> "$coordinate:$moduleVersion" })
        val staging = build.resolve("staging-repo").apply { deleteRecursively() }
        // Also for a public release, so the machine that released can build against it straight away.
        val tasks = if (public) listOf("publishAllPublicationsToStagingRepository", "publishToMavenLocal") else listOf("publishToMavenLocal")
        workspace.gradle(workspace.stackProject, tasks + "-PstackVersion=$version")
        if (public) bucket.uploadStaged(staging)
        println("stack: published stack-catalog and stack-bom $version")
        lap("stack: published")
    }
}
