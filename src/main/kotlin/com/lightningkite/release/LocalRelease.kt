package com.lightningkite.release

/**
 * One local `lk release`: every module into mavenLocal at version `local`, each upstream pin on `local`, and
 * `stack-catalog:local` and `stack-bom:local` at the end.  A module is built again only when it changed since its
 * last local build, whose release record and ABI are in mavenLocal too.  Reads no bucket.
 */
class LocalRelease(private val workspace: Workspace, private val tests: Boolean, private val rebuild: Set<String>) {
    private val mavenLocal = workspace.mavenLocal
    private val journal = workspace.journal

    /** Modules this run built whose new ABI breaks what was built against their last local build. */
    private val broken = mutableSetOf<Coordinate>()

    private fun recordFile(module: Module) = mavenLocal.resolve("${module.mavenPath("local")}-release.properties")
    private fun abiFile(module: Module) = mavenLocal.resolve("${module.mavenPath("local")}-abi.txt")

    fun release() {
        journal.restore()
        val converted = workspace.repos.map { it to discoverModules(it.directory) }.filter { (_, modules) -> modules.isNotEmpty() }
        val unknown = rebuild.filter { name -> converted.none { (repo, modules) -> repo.name == name || modules.any { it.isNamed(name) } } }
        if (unknown.isNotEmpty()) throw UserError("--rebuild: there is no repo or module named ${unknown.joinToString()}")
        var anythingReleased = false
        for ((repo, modules) in converted) {
            try {
                if (releaseRepo(repo, modules)) anythingReleased = true
            } finally {
                journal.restore()
            }
        }
        if (publishStack(converted.map { (repo, _) -> repo })) anythingReleased = true
        if (!anythingReleased) println("Nothing to release.")
    }

    /** Builds what changed in [repo], and its catalog when that is out of date.  False when nothing was published. */
    private fun releaseRepo(repo: Repo, modules: List<Module>): Boolean {
        for (settings in repo.settingsFiles) {
            val text = settings.readText()
            journal.edit(settings, withPins(text, pins(text).mapValues { "local" }))
        }
        // After the pins moved, since they are part of it: a local build always has them on `local`.
        val buildLogicHash = buildLogicHash(repo.directory)
        val hashes = modules.associateWith { inputsHash(it.directory) }
        val records = modules.associateWith { module -> recordFile(module).takeIf { it.isFile }?.let { ReleaseRecord.parse(it.readText()) } }

        val causes = modules.associateWith { module ->
            val record = records.getValue(module)
            buildList {
                if (record == null) add("no local build")
                else {
                    if (record.inputsHash != hashes.getValue(module)) add("inputs changed")
                    if (record.buildLogicHash != buildLogicHash) add("build logic changed")
                    for (dependency in record.dependencies.keys) if (dependency in broken) add("$dependency broke its ABI")
                }
                if (rebuild.any { it == repo.name || module.isNamed(it) }) add("--rebuild")
            }
        }.toMutableMap()
        // Siblings are built in the same Gradle run, before their new ABI is known, so a module is built with every
        // sibling it depends on.
        while (true) {
            val building = causes.filterValues { it.isNotEmpty() }.keys.map { it.coordinate }.toSet()
            val more = modules.filter { causes.getValue(it).isEmpty() }
                .associateWith { module -> checkNotNull(records.getValue(module)).dependencies.keys.filter { it in building }.map { "$it is built too" } }
                .filterValues { it.isNotEmpty() }
            if (more.isEmpty()) break
            causes += more
        }
        val toBuild = modules.filter { causes.getValue(it).isNotEmpty() }

        val catalog = mavenLocal.resolve(catalogFile(repo.name, "local"))
        val listed = if (catalog.isFile) Catalog.parse(catalog.readText()).versions() else mapOf()
        val catalogStale = modules.any { listed[it.coordinate] != "local" }
        if (toBuild.isEmpty() && !catalogStale) return false

        for (module in toBuild) println("  ${module.coordinate} → local: ${causes.getValue(module).joinToString("; ")}")
        // Every module, not only the ones being built: the catalog names them all.
        for (module in modules) journal.edit(module.propertiesFile, withVersion(module.propertiesFile.readText(), "local"))
        val previousAbi = toBuild.associateWith { abiFile(it).takeIf { file -> file.isFile }?.readText() }
        workspace.gradle(repo.directory, buildList {
            if (tests) for (module in toBuild) if (module.directory.resolve("src/test").isDirectory) add(module.task("test"))
            for (module in toBuild) {
                add(module.task("publishToMavenLocal"))
                add(module.task(abiDumpTask))
            }
            add(":publishCatalogPublicationToMavenLocal")
            add("-PcatalogVersion=local")
        })

        for (module in toBuild) {
            val abi = readAbiDump(module)
            val previous = previousAbi.getValue(module)
            if (previous == null || abiDiff(previous, abi, module.experimentalTargets).change == AbiChange.Broken) broken += module.coordinate
            recordFile(module).writeText(ReleaseRecord(hashes.getValue(module), buildLogicHash, gitCommit = null, publishedDependencies(mavenLocal, module, "local")).render())
            abiFile(module).writeText(abi)
        }
        println("${repo.name}: published to mavenLocal ${toBuild.joinToString { it.artifact }.ifEmpty { "its catalog" }}")
        return true
    }

    /** Publishes the stack catalog and BOM from every repo's local catalog, unless they list what they did before. */
    private fun publishStack(repos: List<Repo>): Boolean {
        val merged = mergeCatalogs(repos.map { repo -> Catalog.parse(mavenLocal.resolve(catalogFile(repo.name, "local")).readText()) })
        val previous = mavenLocal.resolve(stackCatalogFile("local")).takeIf { it.isFile }?.let { Catalog.parse(it.readText()).versions() - stackBom }
        if (previous == merged.versions()) return false
        publishStackProject(workspace, merged, "local", listOf("publishToMavenLocal"))
        println("stack: published stack-catalog and stack-bom local")
        return true
    }
}
