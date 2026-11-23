package com.lightningkite.release

import java.io.File

/** A mistake by the person running the tool, or a failed build: reported as its message, without a stack trace. */
class UserError(message: String) : Exception(message)

/** A library repo.  [bucket] is where its releases go. */
class Repo(val name: String, val directory: File, val bucket: String) {
    /** The files that pin upstream catalogs. */
    val settingsFiles: List<File> get() = listOf("settings.gradle.kts", "buildSrc/settings.gradle.kts").map { directory.resolve(it) }.filter { it.exists() }
}

/**
 * The repos and tools as they are on this machine.  [home] is this project.  With [rehearsalBuckets],
 * `s3://<bucket>` is `<directory>/<bucket>`, and every build also resolves from there.
 */
class Workspace(
    val home: File,
    /** In release order: each depends only on the ones before it. */
    val repos: List<Repo>,
    /** Where the stack catalog and BOM go. */
    val stackBucket: String,
    val mavenLocal: File,
    val rehearsalBuckets: File?,
    /** Added to every Gradle run, e.g. `--offline`. */
    val gradleArguments: List<String>,
) {
    /** The Gradle project that publishes the stack catalog and BOM. */
    val stackProject: File = home.resolve("../lightning-kite-stack-catalog").normalize()
    val journal = Journal(home.resolve("build/lk-journal.json"))

    /** What the last command left to read: `gradle.log`, and `abi/<group>/<artifact>.diff` for each changed ABI. */
    val outputDirectory: File = home.resolve("build/lk-output").apply { deleteRecursively(); mkdirs() }
    private val gradleLog = outputDirectory.resolve("gradle.log")

    fun repo(name: String): Repo = repos.singleOrNull { it.name == name } ?: throw UserError("No repo `$name`; expected one of ${repos.joinToString { it.name }}")

    /** [forWriting] fails at once without credentials, so a release that can't upload stops before it starts. */
    fun open(bucket: String, forWriting: Boolean): MavenRepository {
        val name = bucket.removePrefix("s3://")
        return if (rehearsalBuckets == null) S3MavenRepository(name, forWriting) else DirectoryMavenRepository(rehearsalBuckets.resolve(name))
    }

    /** Runs `./gradlew` in [directory]; throws when the build fails. */
    fun gradle(directory: File, arguments: List<String>) {
        val rehearsal = rehearsalBuckets?.let { listOf("--init-script", home.resolve("rehearsal.init.gradle.kts").path, "-Dlk.rehearsalBuckets=$it") }.orEmpty()
        // Passed even when it's the default, so Gradle publishes exactly where the tool looks.
        val command = listOf("./gradlew", "-Dmaven.repo.local=$mavenLocal") + gradleArguments + rehearsal + arguments
        println("${directory.name}: ./gradlew ${arguments.joinToString(" ")}")
        gradleLog.appendText("\n=== ${directory.name}: ./gradlew ${arguments.joinToString(" ")}\n")
        val exit = ProcessBuilder(command).directory(directory).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(gradleLog)).start().waitFor()
        if (exit != 0) {
            // Gradle ends with what went wrong.
            val tail = gradleLog.readLines().takeLast(40).joinToString("\n")
            throw UserError("$tail\n\n${directory.name}: `./gradlew ${arguments.joinToString(" ")}` failed; the whole output is in $gradleLog")
        }
    }
}

/** Runs a command, returning what it printed; throws when it fails. */
fun exec(directory: File, vararg command: String): String {
    val process = ProcessBuilder(*command).directory(directory).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0) { "`${command.joinToString(" ")}` failed in $directory:\n$output" }
    return output
}
