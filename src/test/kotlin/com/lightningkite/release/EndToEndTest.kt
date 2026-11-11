package com.lightningkite.release

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the tool with real Gradle against two small repos (`up`, and `down` which depends on it), a directory
 * standing in for the bucket and another for mavenLocal.  One long story, because each step needs the releases
 * made by the ones before it.  Slow: every release is a few Gradle runs.
 */
class EndToEndTest {
    // Gradle runs tests in the project directory.
    private val project = File("").canonicalFile
    private val work: File = Files.createTempDirectory("lk-e2e").toFile().canonicalFile
    private val tool = work.resolve("tool")
    private val up = work.resolve("up")
    private val down = work.resolve("down")
    private val buckets = work.resolve("buckets")
    private val bucket = buckets.resolve("stack")
    private val mavenLocal = work.resolve("m2")
    private val journalFile = tool.resolve("build/lk-journal.json")
    private val workspace = Workspace(
        home = tool,
        repos = listOf(Repo("up", up, "s3://stack", inStack = true), Repo("down", down, "s3://stack", inStack = true)),
        mavenLocal = mavenLocal,
        rehearsalBuckets = buckets,
        gradleArguments = listOf("--offline"),
    )

    private fun write(path: String, text: String) = work.resolve(path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")

    /** What the last release printed.  Gradle's own output goes straight to the terminal, not here. */
    private var output = listOf<String>()
    private var gradleRuns = 0

    private fun release(public: Boolean, tests: Boolean = public, rebuild: Set<String> = setOf()) {
        val printed = ByteArrayOutputStream()
        val stdout = System.out
        System.setOut(PrintStream(printed, true))
        try {
            ReleaseRun(workspace, public = public, tests = tests, rebuild = rebuild, planOnly = false).release()
        } finally {
            System.setOut(stdout)
            print(printed)
            output = printed.toString().lines()
            gradleRuns += output.count { "./gradlew" in it }
        }
    }

    private fun git(repo: File, vararg arguments: String) = exec(repo, "git", *arguments)
    private fun commit(repo: File) {
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "change")
    }

    private fun versions(coordinate: String): List<String> =
        bucket.resolve("${coordinate.replace('.', '/').replace(':', '/')}/maven-metadata.xml").takeIf { it.exists() }?.let { mavenMetadataVersions(it.readText()) }.orEmpty()

    private fun latest(coordinate: String): String? = versions(coordinate).lastOrNull()
    private fun record(root: File, coordinate: String, version: String): ReleaseRecord? {
        val artifact = coordinate.substringAfter(':')
        return root.resolve("${coordinate.replace('.', '/').replace(':', '/')}/$version/$artifact-$version-release.properties").takeIf { it.isFile }?.let { ReleaseRecord.parse(it.readText()) }
    }

    private fun stackCatalog(): Catalog = latest("com.lightningkite:stack-catalog")!!.let { Catalog.parse(bucket.resolve("com/lightningkite/stack-catalog/$it/stack-catalog-$it.toml").readText()) }

    private val rootScript = """
        import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
        import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

        plugins {
            kotlin("jvm") version "2.4.21" apply false
            `version-catalog`
            `maven-publish`
        }

        val published = subprojects.filter { it.file("gradle.properties").isFile }
        catalog {
            versionCatalog {
                published.forEach { library(it.name, "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}") }
            }
        }
        publishing {
            publications.create<MavenPublication>("catalog") {
                from(components["versionCatalog"])
                groupId = "com.lightningkite.stack"
                artifactId = "${'$'}{rootProject.name}-catalog"
                version = providers.gradleProperty("catalogVersion").getOrElse("unspecified")
            }
            repositories { maven { name = "staging"; url = uri(layout.buildDirectory.dir("staging-repo")) } }
        }

        subprojects {
            apply(plugin = "org.jetbrains.kotlin.jvm")
            apply(plugin = "maven-publish")
            extensions.configure<KotlinJvmProjectExtension> { @OptIn(ExperimentalAbiValidation::class) abiValidation() }
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("maven") { from(components["java"]) }
                repositories { maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("staging-repo")) } }
            }
            tasks.withType<Test>().configureEach { useJUnitPlatform() }
        }
    """

    private fun createRepos() {
        for (repo in listOf("up", "down", "lightning-kite-stack-catalog")) {
            project.resolve("gradlew").copyTo(work.resolve("$repo/gradlew")).setExecutable(true)
            project.resolve("gradle/wrapper").copyRecursively(work.resolve("$repo/gradle/wrapper"))
        }
        project.resolve("../lightning-kite-stack-catalog/build.gradle.kts").copyTo(workspace.stackProject.resolve("build.gradle.kts"))
        project.resolve("../lightning-kite-stack-catalog/settings.gradle.kts").copyTo(workspace.stackProject.resolve("settings.gradle.kts"))
        project.resolve("rehearsal.init.gradle.kts").copyTo(tool.resolve("rehearsal.init.gradle.kts"))

        write("up/settings.gradle.kts", """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral() } }
            rootProject.name = "up"
            include(":base", ":util", ":impl")
        """)
        write("up/build.gradle.kts", rootScript)
        write("up/base/gradle.properties", "group=com.example.up\nversion=1000.0.0")
        write("up/base/build.gradle.kts", "")
        write("up/base/src/main/kotlin/Base.kt", "package up\n\nclass Base {\n    fun a(): Int = 1\n}")
        write("up/util/gradle.properties", "group=com.example.up\nversion=1000.0.0")
        write("up/util/build.gradle.kts", """dependencies { "api"(project(":base")) }""")
        write("up/util/src/main/kotlin/Util.kt", "package up\n\nclass Util {\n    fun base(): Base = Base()\n}")
        write("up/impl/gradle.properties", "group=com.example.up\nversion=1000.0.0")
        write("up/impl/build.gradle.kts", """
            dependencies {
                "implementation"(project(":base"))
                "testImplementation"(kotlin("test"))
            }
        """)
        write("up/impl/src/main/kotlin/Impl.kt", "package up\n\nclass Impl {\n    fun n(): Int = Base().a()\n}")
        write("up/impl/src/test/kotlin/ImplTest.kt", "package up\n\nclass ImplTest {\n    @kotlin.test.Test fun works() = kotlin.test.assertTrue(true)\n}")

        write("down/settings.gradle.kts", """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement {
                repositories { mavenLocal(); mavenCentral() }
                versionCatalogs { create("up") { from("com.lightningkite.stack:up-catalog:5.4.1") } }
            }
            rootProject.name = "down"
            include(":app", ":solo")
        """)
        write("down/build.gradle.kts", rootScript)
        write("down/app/gradle.properties", "group=com.example.down\nversion=1000.0.0")
        write("down/app/build.gradle.kts", """dependencies { "api"(up.base) }""")
        write("down/app/src/main/kotlin/App.kt", "package down\n\nclass App {\n    fun n(): Int = up.Base().a()\n}")
        write("down/solo/gradle.properties", "group=com.example.down\nversion=1000.0.0")
        write("down/solo/build.gradle.kts", "")
        write("down/solo/src/main/kotlin/Solo.kt", "package down\n\nclass Solo")

        // A catalog published before this tool, under the library's own version: not one of its releases.
        DirectoryMavenRepository(bucket).put(mapOf(
            "com/lightningkite/stack/up-catalog/maven-metadata.xml" to mergeMavenMetadata(null, "com.lightningkite.stack", "up-catalog", "5.4.1", ZonedDateTime.now()),
        ))

        for (repo in listOf(up, down)) {
            repo.resolve(".gitignore").writeText("build\n.gradle\n.kotlin\n")
            git(repo, "init", "-q")
            git(repo, "config", "user.email", "test@example.com")
            git(repo, "config", "user.name", "Test")
            commit(repo)
        }
    }

    private fun pin() = pins(down.resolve("settings.gradle.kts").readText()).getValue("up")

    @Test
    fun story() {
        createRepos()

        // The first release: everything goes out at the written version.
        release(public = true)
        for (module in listOf("up:base", "up:util", "up:impl", "down:app", "down:solo")) assertEquals(listOf("1000.0.0"), versions("com.example.$module"), module)
        assertEquals(Dependency("1000.0.0", Scope.Api), record(bucket, "com.example.up:util", "1000.0.0")!!.dependencies["com.example.up:base"])
        assertEquals(Dependency("1000.0.0", Scope.Implementation), record(bucket, "com.example.up:impl", "1000.0.0")!!.dependencies["com.example.up:base"])
        assertEquals(Dependency("1000.0.0", Scope.Api), record(bucket, "com.example.down:app", "1000.0.0")!!.dependencies["com.example.up:base"])
        assertEquals("1000.0.0", latest("com.lightningkite.stack:up-catalog"))
        assertEquals("1000.0.0", pin(), "the pin on a catalog from before the tool moved to its first release")
        assertTrue(mavenLocal.resolve("com/example/up/base/1000.0.0/base-1000.0.0.jar").isFile, "a public release is in mavenLocal too")
        assertTrue(mavenLocal.resolve("com/lightningkite/stack-catalog/1000.0.0").isDirectory)
        assertEquals("Release app 1000.0.0, solo 1000.0.0", git(down, "log", "-1", "--format=%s"))
        assertEquals(Catalog.Library("com.example.up:base", "1000.0.0"), stackCatalog().libraries["up-base"])
        assertEquals(Catalog.Library("com.lightningkite:stack-bom", latest("com.lightningkite:stack-catalog")), stackCatalog().libraries["stack-bom"])
        assertTrue(bucket.resolve("com/lightningkite/stack-bom/${latest("com.lightningkite:stack-catalog")}").isDirectory)
        assertTrue(git(up, "status", "--porcelain").isEmpty() && git(down, "status", "--porcelain").isEmpty())

        // Nothing changed: no Gradle at all.
        val runsBefore = gradleRuns
        val stackBefore = versions("com.lightningkite:stack-catalog")
        release(public = true)
        assertEquals(runsBefore, gradleRuns)
        assertEquals(stackBefore, versions("com.lightningkite:stack-catalog"))

        // Modules released without their catalog, as an older version of this tool did: the catalog goes out alone.
        bucket.resolve("com/lightningkite/stack/up-catalog/1000.0.0").deleteRecursively()
        bucket.resolve("com/lightningkite/stack/up-catalog/maven-metadata.xml")
            .writeText(mergeMavenMetadata(null, "com.lightningkite.stack", "up-catalog", "5.4.1", ZonedDateTime.now()))
        val upCommit = git(up, "rev-parse", "HEAD")
        release(public = true)
        assertEquals(listOf("5.4.1", "1000.0.0"), versions("com.lightningkite.stack:up-catalog"))
        assertEquals("1000.0.0", Catalog.parse(bucket.resolve("com/lightningkite/stack/up-catalog/1000.0.0/up-catalog-1000.0.0.toml").readText()).versions()["com.example.up:base"])
        assertEquals(listOf("1000.0.0"), versions("com.example.up:base"))
        assertEquals(upCommit, git(up, "rev-parse", "HEAD"))
        assertTrue(output.any { it == "up: released no modules, and up-catalog 1000.0.0" })
        assertEquals(stackBefore, versions("com.lightningkite:stack-catalog"))

        // A change that leaves the ABI alone is a patch of that module only.
        write("up/base/src/main/kotlin/Base.kt", "package up\n\nclass Base {\n    fun a(): Int = 2\n}")
        commit(up)
        release(public = true)
        assertEquals("1000.0.1", latest("com.example.up:base"))
        for (module in listOf("up:util", "up:impl", "down:app", "down:solo")) assertEquals(listOf("1000.0.0"), versions("com.example.$module"), module)
        assertEquals("version=1000.0.1", up.resolve("base/gradle.properties").readLines().last())
        assertEquals("Release base 1000.0.1", git(up, "log", "-1", "--format=%s"))
        assertEquals("1000.0.1", stackCatalog().versions()["com.example.up:base"])
        // Catalog versions follow what moved in them, by the modules' own rules.
        assertEquals("1000.0.1", latest("com.lightningkite.stack:up-catalog"))
        assertEquals("1000.0.1", latest("com.lightningkite:stack-catalog"))

        // An addition is a minor.
        write("up/base/src/main/kotlin/Base.kt", "package up\n\nclass Base {\n    fun a(): Int = 2\n    fun b(): Int = 3\n}")
        commit(up)
        release(public = true)
        assertEquals("1000.1.0", latest("com.example.up:base"))
        assertEquals(listOf("1000.0.0"), versions("com.example.up:util"))
        assertEquals("1000.1.0", latest("com.lightningkite.stack:up-catalog"))
        assertEquals("1000.1.0", latest("com.lightningkite:stack-catalog"))

        // A binary break is a major, which cascades: through `api` as a major, through `implementation` as a patch.
        write("up/base/src/main/kotlin/Base.kt", "package up\n\nclass Base {\n    fun a(extra: Int = 0): Int = 2 + extra\n    fun b(): Int = 3\n}")
        commit(up)
        val pinBefore = pin()
        release(public = true)
        assertEquals("1001.0.0", latest("com.example.up:base"))
        assertEquals("1001.0.0", latest("com.example.up:util"))
        assertEquals("1000.0.1", latest("com.example.up:impl"))
        assertEquals("1001.0.0", latest("com.example.down:app"))
        assertEquals(listOf("1000.0.0"), versions("com.example.down:solo"))
        assertEquals("1000.0.0", pinBefore, "upstream patches and minors never moved the pin")
        assertEquals("1001.0.0", latest("com.lightningkite.stack:up-catalog"))
        assertEquals("1001.0.0", pin())
        assertEquals("1001.0.0", latest("com.lightningkite.stack:down-catalog"))
        assertEquals("1001.0.0", latest("com.lightningkite:stack-catalog"))
        assertEquals(Dependency("1001.0.0", Scope.Api), record(bucket, "com.example.down:app", "1001.0.0")!!.dependencies["com.example.up:base"])
        assertEquals("1001.0.0", stackCatalog().versions()["com.example.down:app"])

        // A break the downstream repo doesn't compile against stops the run there, with no stack catalog published.
        write("up/base/src/main/kotlin/Base.kt", "package up\n\nclass Base {\n    fun renamed(): Int = 2\n    fun b(): Int = 3\n}")
        write("up/impl/src/main/kotlin/Impl.kt", "package up\n\nclass Impl {\n    fun n(): Int = Base().renamed()\n}")
        commit(up)
        val stackBeforeBreak = versions("com.lightningkite:stack-catalog")
        assertFails { release(public = true) }
        assertEquals("1002.0.0", latest("com.example.up:base"))
        assertEquals("1001.0.0", latest("com.example.down:app"))
        assertEquals(stackBeforeBreak, versions("com.lightningkite:stack-catalog"))
        assertTrue(git(down, "status", "--porcelain").isEmpty(), "the failed build's version and pin edits were undone")
        assertFalse(journalFile.exists())
        // Fixing the downstream source and running again finishes the cascade.
        write("down/app/src/main/kotlin/App.kt", "package down\n\nclass App {\n    fun n(): Int = up.Base().renamed()\n}")
        commit(down)
        release(public = true)
        assertEquals("1002.0.0", latest("com.example.down:app"))
        assertEquals(stackBeforeBreak.size + 1, versions("com.lightningkite:stack-catalog").size)

        // A failing test aborts a public release before anything is committed or uploaded.
        write("up/impl/src/main/kotlin/Impl.kt", "package up\n\nclass Impl {\n    fun n(): Int = Base().renamed() + 1\n}")
        write("up/impl/src/test/kotlin/ImplTest.kt", "package up\n\nclass ImplTest {\n    @kotlin.test.Test fun works() = kotlin.test.assertTrue(false)\n}")
        commit(up)
        val implBefore = versions("com.example.up:impl")
        val commitBefore = git(up, "rev-parse", "HEAD")
        assertFails { release(public = true) }
        assertEquals(implBefore, versions("com.example.up:impl"))
        assertEquals(commitBefore, git(up, "rev-parse", "HEAD"))
        assertTrue(git(up, "status", "--porcelain").isEmpty())
        // Local releases run no tests unless asked, and a failing one there also puts the files back.
        assertFails { release(public = false, tests = true) }
        assertTrue(git(up, "status", "--porcelain").isEmpty())
        write("up/impl/src/test/kotlin/ImplTest.kt", "package up\n\nclass ImplTest {\n    @kotlin.test.Test fun works() = kotlin.test.assertTrue(true)\n}")
        commit(up)
        release(public = true)
        val implVersion = latest("com.example.up:impl")!!
        assertEquals(implBefore.size + 1, versions("com.example.up:impl").size)

        // A local release: `<next>-local` in mavenLocal, nothing committed, the bucket untouched.
        write("up/base/src/main/kotlin/Base.kt", "package up\n\nclass Base {\n    fun renamed(): Int = 5\n    fun b(): Int = 3\n}")
        release(public = false)
        val firstLocal = record(mavenLocal, "com.example.up:base", "1002.0.1-local")!!
        assertEquals("1002.0.0", latest("com.example.up:base"))
        assertEquals("M base/src/main/kotlin/Base.kt", git(up, "status", "--porcelain"))
        assertNull(record(mavenLocal, "com.example.up:impl", "$implVersion-local"), "unchanged modules aren't published locally")
        assertEquals("1002.0.1-local", Catalog.parse(mavenLocal.resolve("com/lightningkite/stack-catalog/local/stack-catalog-local.toml").readText()).versions()["com.example.up:base"])
        // Again with nothing changed: no Gradle.
        val runsBeforeRepeat = gradleRuns
        release(public = false)
        assertEquals(runsBeforeRepeat, gradleRuns)
        // More changes of the same level republish the same version.
        write("up/base/src/main/kotlin/Base.kt", "package up\n\nclass Base {\n    fun renamed(): Int = 6\n    fun b(): Int = 3\n}")
        release(public = false)
        assertTrue(record(mavenLocal, "com.example.up:base", "1002.0.1-local")!!.inputsHash != firstLocal.inputsHash)
        assertFalse(mavenLocal.resolve("com/example/up/base/1002.0.2-local").exists())

        // --rebuild publishes a local build again although nothing changed.
        val runsBeforeRebuild = gradleRuns
        release(public = false, rebuild = setOf("base"))
        assertTrue(gradleRuns > runsBeforeRebuild)

        // A local catalog that no longer matches what it should list is published again by itself.
        val localCatalog = mavenLocal.resolve("com/lightningkite/stack/up-catalog/local/up-catalog-local.toml")
        localCatalog.writeText(localCatalog.readText().replace("1002.0.1-local", "1002.0.0-local"))
        release(public = false)
        assertEquals("1002.0.1-local", Catalog.parse(localCatalog.readText()).versions()["com.example.up:base"])
        assertTrue(output.any { it == "up: published to mavenLocal its catalog" })

        // A name --rebuild doesn't know is an error, not a silent no-op.
        assertFailsWith<UserError> { release(public = false, rebuild = setOf("bsae")) }

        // A public release refuses a dirty tree.
        assertFails { release(public = true) }
        commit(up)

        // An upload that dies after the commit heals on the next run: same version, published again.
        // A directory where the release record goes makes writing the record fail.
        val recordPath = bucket.resolve("com/example/up/base/1002.0.1/base-1002.0.1-release.properties").apply { mkdirs() }
        assertFails { release(public = true) }
        assertEquals("Release base 1002.0.1", git(up, "log", "-1", "--format=%s"))
        assertNull(record(bucket, "com.example.up:base", "1002.0.1"))
        recordPath.delete()
        release(public = true)
        assertEquals(1, versions("com.example.up:base").count { it == "1002.0.1" })
        assertEquals(git(up, "rev-parse", "HEAD"), record(bucket, "com.example.up:base", "1002.0.1")!!.gitCommit)
        assertEquals("Release base 1002.0.1", git(up, "log", "-1", "--format=%s"), "no second commit")

        // --rebuild releases an unchanged module as a patch.
        release(public = true, rebuild = setOf("solo"))
        assertEquals("1000.0.1", latest("com.example.down:solo"))
    }
}
