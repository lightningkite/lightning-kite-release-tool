package com.lightningkite.release

import com.lightningkite.kotlinercli.Description
import com.lightningkite.kotlinercli.WrongCliArgumentsException
import com.lightningkite.kotlinercli.cliReturning
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** The repos and tools as they are on this machine.  With [rehearsalBuckets], `s3://<bucket>` is `<directory>/<bucket>`. */
private class Workspace(rehearsalBuckets: File?) {
    // The `lk` script changes into this project before it starts the tool.
    val home: File = File("").absoluteFile.also { if (!it.resolve("lk").isFile) throw UserError("Start the tool with its `lk` script") }
    private val rehearsalBuckets = rehearsalBuckets?.absoluteFile

    private val stackBucket = "s3://lightningkite-maven"

    // The libraries are checked out next to this project.  In release order: each depends only on the ones before it.
    val repos = listOf(
        Repo("service-abstractions", home.resolve("../service-abstractions").normalize(), stackBucket, inStack = true),
        Repo("lightning-server", home.resolve("../lightning-server").normalize(), stackBucket, inStack = true),
        Repo("reactive", home.resolve("../reactive").normalize(), stackBucket, inStack = true),
        Repo("kiteui", home.resolve("../kiteui").normalize(), stackBucket, inStack = true),
        Repo("lightning-server-kiteui", home.resolve("../lightning-server-kiteui").normalize(), stackBucket, inStack = true),
        Repo("lightning-bot", home.resolve("../lightning-bot").normalize(), "s3://lightning-bot-maven", inStack = false),
    )

    fun repo(name: String): Repo = repos.singleOrNull { it.name == name } ?: throw UserError("No repo `$name`; expected one of ${repos.joinToString { it.name }}")

    fun open(bucket: String, forWriting: Boolean): MavenRepository {
        val name = bucket.removePrefix("s3://")
        return if (rehearsalBuckets == null) S3MavenRepository(name, forWriting) else DirectoryMavenRepository(rehearsalBuckets.resolve(name))
    }

    val gradle = Gradle { directory, tasks ->
        // A rehearsal's builds must find what earlier repos "released" into the directory buckets.
        val rehearsal = this.rehearsalBuckets?.let { listOf("--init-script", home.resolve("rehearsal.init.gradle.kts").path, "-Dlk.rehearsalBuckets=$it") }.orEmpty()
        val extra = System.getenv("LK_GRADLE_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        val exit = ProcessBuilder(listOf("./gradlew") + extra + rehearsal + tasks).directory(directory).inheritIO().start().waitFor()
        if (exit != 0) throw UserError("${directory.name}: `./gradlew ${tasks.joinToString(" ")}` failed")
    }

    val releaser = Releaser(
        repos = repos,
        mavenLocal = File(System.getProperty("user.home"), ".m2/repository"),
        openRepository = ::open,
        gradle = gradle,
        stackProject = home.resolve("../lightning-kite-stack-catalog").normalize(),
        journal = Journal(home.resolve("build/lk-journal.json")),
    )
}

// kotliner-cli can't take a list of names, so --rebuild is one comma-separated argument.
private fun names(commaSeparated: String): Set<String> = commaSeparated.split(',').filter { it.isNotBlank() }.toSet()

@Description("Prints what a release would do, module by module, and why.  Leaves nothing edited or published.")
fun plan(public: Boolean = false, rebuild: String = "", buckets: File? = null) =
    Workspace(buckets).releaser.release(Options(public = public, plan = true, rebuild = names(rebuild)))

@Description(
    "Publishes changed modules: into mavenLocal as <next>-local, or with --public for real (tests, upload, a commit; never a push).  " +
        "--rebuild <repos or modules, comma-separated> releases them although unchanged.  --tests false skips the tests of a public release."
)
fun release(public: Boolean = false, tests: Boolean = public, rebuild: String = "", buckets: File? = null) =
    Workspace(buckets).releaser.release(Options(public = public, tests = tests, rebuild = names(rebuild)))

@Description("Moves a repo's upstream catalog pins to the latest released catalogs.")
fun pin(repo: String, buckets: File? = null) {
    val workspace = Workspace(buckets)
    for ((settings, pins) in pinToLatest(workspace.repo(repo), workspace.repos, workspace::open)) println("$repo/$settings: $pins")
}

@Description("Writes <epoch>000.0.0 into every module of a repo.")
fun epoch(repo: String, epoch: Int) {
    for (module in writeEpoch(Workspace(null).repo(repo), epoch)) println("${module.coordinate} ${Version.epoch(epoch)}")
}

@Description("Generates a repo's Dokka output and uploads it.")
fun docs(repo: String, buckets: File? = null) {
    val workspace = Workspace(buckets)
    val target = workspace.repo(repo)
    val modules = discoverModules(target.directory)
    workspace.gradle.run(target.directory, modules.map { it.task("dokkaGeneratePublicationHtml") })
    workspace.open(target.bucket, true).use { bucket ->
        for (module in modules) println("${module.coordinate}: docs at ${uploadDocs(bucket, module)}")
    }
}

/**
 * Every command also takes `--buckets <directory>` to rehearse without S3.  `LK_GRADLE_ARGS` adds arguments to
 * every Gradle run the tool starts.
 */
fun main(arguments: Array<String>) {
    try {
        cliReturning(arguments, available = listOf(::plan, ::release, ::pin, ::epoch, ::docs), useInteractive = false)
    } catch (e: WrongCliArgumentsException) {
        exitProcess(2)
    } catch (e: InvocationTargetException) {
        val cause = e.cause ?: throw e
        if (cause !is UserError) throw cause
        System.err.println(cause.message)
        exitProcess(1)
    }
}
