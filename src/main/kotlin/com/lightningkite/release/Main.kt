package com.lightningkite.release

import com.lightningkite.kotlinercli.Description
import com.lightningkite.kotlinercli.WrongCliArgumentsException
import com.lightningkite.kotlinercli.cliReturning
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** This machine.  The libraries are checked out next to this project. */
private fun workspace(rehearsalBuckets: File?): Workspace {
    // The `lk` script changes into this project before it starts the tool.
    val home = File("").absoluteFile
    if (!home.resolve("lk").isFile) throw UserError("Start the tool with its `lk` script")
    val stackBucket = "s3://lightningkite-maven"
    return Workspace(
        home = home,
        repos = listOf(
            Repo("kotliner-cli", home.resolve("../kotliner-cli").normalize(), stackBucket),
            Repo("kotlinx-serialization-csv-durable", home.resolve("../kotlinx-serialization-csv-durable").normalize(), stackBucket),
            Repo("service-abstractions", home.resolve("../service-abstractions").normalize(), stackBucket),
            Repo("lightning-server", home.resolve("../lightning-server").normalize(), stackBucket),
            Repo("reactive", home.resolve("../reactive").normalize(), stackBucket),
            Repo("kiteui", home.resolve("../kiteui").normalize(), stackBucket),
            Repo("lightning-server-kiteui", home.resolve("../lightning-server-kiteui").normalize(), stackBucket),
            Repo("lightning-bot", home.resolve("../lightning-bot").normalize(), "s3://lightning-bot-maven"),
            Repo("human-relay", home.resolve("../human-relay").normalize(), stackBucket),
        ),
        stackBucket = stackBucket,
        mavenLocal = File(System.getProperty("user.home"), ".m2/repository"),
        rehearsalBuckets = rehearsalBuckets?.absoluteFile,
        gradleArguments = System.getenv("LK_GRADLE_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
    )
}

// kotliner-cli can't take a list of names, so --rebuild and --only are each one comma-separated argument.
private fun names(commaSeparated: String): Set<String> = commaSeparated.split(',').filter { it.isNotBlank() }.toSet()

@Description("Prints what a public release would do, module by module, and why.  Leaves nothing edited or published.")
fun plan(rebuild: String = "", only: String = "", buckets: File? = null) =
    PublicRelease(workspace(buckets), tests = false, rebuild = names(rebuild), only = names(only), dropTargets = false, planOnly = true).release()

@Description(
    "Publishes changed modules: into mavenLocal at version `local` (reading no bucket), or with --public for real (tests, upload, a commit; never a push).  " +
        "--rebuild <repos or modules, comma-separated> releases them although unchanged.  --tests runs the tests; a public release runs them unless --tests false.  " +
        "Public only: --only <repos, comma-separated> releases just those, and refuses if an earlier repo has changes to release first; it publishes no stack catalog.  " +
        "--dropTargets lets a release go out without a Kotlin Multiplatform target its last release had.  --buckets <directory> rehearses without S3."
)
fun release(public: Boolean = false, tests: Boolean = public, rebuild: String = "", only: String = "", dropTargets: Boolean = false, buckets: File? = null) {
    if (public) {
        PublicRelease(workspace(buckets), tests = tests, rebuild = names(rebuild), only = names(only), dropTargets = dropTargets, planOnly = false).release()
        return
    }
    if (only.isNotEmpty() || dropTargets || buckets != null) throw UserError("--only, --dropTargets and --buckets are for --public; a local release reads no bucket and skips what didn't change")
    LocalRelease(workspace(rehearsalBuckets = null), tests = tests, rebuild = names(rebuild)).release()
}

@Description("Moves a repo's upstream catalog pins to the latest released catalogs.")
fun pin(repo: String, buckets: File? = null) {
    val workspace = workspace(buckets)
    for ((settings, pins) in pinToLatest(workspace, workspace.repo(repo))) println("$repo/$settings: $pins")
}

@Description("Writes <epoch>000.0.0 into every module of a repo.")
fun epoch(repo: String, epoch: Int) {
    val version = Version.epoch(epoch).toString()
    for (module in discoverModules(workspace(null).repo(repo).directory)) {
        module.propertiesFile.writeText(withVersion(module.propertiesFile.readText(), version))
        println("${module.coordinate} $version")
    }
}

@Description("Generates a repo's Dokka output and uploads it.")
fun docs(repo: String, buckets: File? = null) {
    val workspace = workspace(buckets)
    val target = workspace.repo(repo)
    val modules = discoverModules(target.directory)
    workspace.gradle(target.directory, modules.map { it.task("dokkaGeneratePublicationHtml") })
    workspace.open(target.bucket, forWriting = true).use { bucket ->
        for (module in modules) println("${module.coordinate}: docs at ${uploadDocs(bucket, module)}")
    }
}

/**
 * Every command also takes `--buckets <directory>` to rehearse without S3.  `LK_GRADLE_ARGS` adds arguments to
 * every Gradle run the tool starts.
 */
fun main(arguments: Array<String>) {
    lap("started")
    try {
        cliReturning(arguments, available = listOf(::plan, ::release, ::pin, ::epoch, ::docs), useInteractive = false)
        lap("done")
        // Netty's global executor thread would keep the process alive for another second.
        exitProcess(0)
    } catch (e: WrongCliArgumentsException) {
        exitProcess(2)
    } catch (e: InvocationTargetException) {
        val cause = e.cause ?: throw e
        if (cause !is UserError) throw cause
        System.err.println(cause.message)
        exitProcess(1)
    }
}
