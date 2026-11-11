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
            Repo("service-abstractions", home.resolve("../service-abstractions").normalize(), stackBucket, inStack = true),
            Repo("lightning-server", home.resolve("../lightning-server").normalize(), stackBucket, inStack = true),
            Repo("reactive", home.resolve("../reactive").normalize(), stackBucket, inStack = true),
            Repo("kiteui", home.resolve("../kiteui").normalize(), stackBucket, inStack = true),
            Repo("lightning-server-kiteui", home.resolve("../lightning-server-kiteui").normalize(), stackBucket, inStack = true),
            Repo("lightning-bot", home.resolve("../lightning-bot").normalize(), "s3://lightning-bot-maven", inStack = false),
        ),
        mavenLocal = File(System.getProperty("user.home"), ".m2/repository"),
        rehearsalBuckets = rehearsalBuckets?.absoluteFile,
        gradleArguments = System.getenv("LK_GRADLE_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
    )
}

// kotliner-cli can't take a list of names, so --rebuild is one comma-separated argument.
private fun names(commaSeparated: String): Set<String> = commaSeparated.split(',').filter { it.isNotBlank() }.toSet()

@Description("Prints what a release would do, module by module, and why.  Leaves nothing edited or published.")
fun plan(public: Boolean = false, rebuild: String = "", buckets: File? = null) =
    ReleaseRun(workspace(buckets), public = public, tests = false, rebuild = names(rebuild), planOnly = true).release()

@Description(
    "Publishes changed modules: into mavenLocal as <next>-local, or with --public for real (tests, upload, a commit; never a push).  " +
        "--rebuild <repos or modules, comma-separated> releases them although unchanged.  --tests false skips the tests of a public release."
)
fun release(public: Boolean = false, tests: Boolean = public, rebuild: String = "", buckets: File? = null) =
    ReleaseRun(workspace(buckets), public = public, tests = tests, rebuild = names(rebuild), planOnly = false).release()

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
