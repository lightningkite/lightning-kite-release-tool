package com.lightningkite.release

import java.io.File

/** Runs `./gradlew <arguments>` in a repo; throws when the build fails. */
fun interface Gradle {
    fun run(directory: File, arguments: List<String>)
}

/** Writes a module's ABI dump into `build/kotlin/abi`, for Kotlin Multiplatform and JVM modules alike. */
const val abiDumpTask = "internalDumpKotlinAbi"

/** The module's ABI dumps as [abiDumpTask] left them, concatenated in path order, each under a `# <path>` header. */
fun readAbiDump(module: Module): String {
    val directory = module.directory.resolve("build/kotlin/abi")
    val dumps = directory.walkTopDown().filter { it.isFile && it.extension == "api" }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.toList()
    check(dumps.isNotEmpty()) { "${module.gradlePath} has no ABI dump in $directory; is `abiValidation()` on in its build script?" }
    return dumps.joinToString("") { "# ${it.relativeTo(directory).invariantSeparatorsPath}\n${it.readText()}\n" }
}

/**
 * What the rolling deploy before this tool ran: the `test` task of JVM modules.  Kotlin Multiplatform modules have
 * no task of that name, and their tests (browser, simulator) were never part of a release.
 */
fun Module.testTasks(): List<String> = if (directory.resolve("src/test").isDirectory) listOf(task("test")) else listOf()
