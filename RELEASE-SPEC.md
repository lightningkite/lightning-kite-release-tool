# Releasing the Lightning Kite libraries

How the six library repos (service-abstractions, lightning-server, reactive, kiteui, lightning-server-kiteui,
lightning-bot) are versioned and released.  The tool is `./lk`; its source is `src/`.  For what to type,
see the README.

Goal: a release costs time only for the modules that really changed.  An unchanged module starts no Gradle, builds
nothing and uploads nothing.  The Gradle builds are plain; one tool owns every release decision.

## Shape

| Piece | Owns |
|---|---|
| Each library repo | Plain Gradle: builds and publishes the versions written in its files.  No release logic, no AWS code. |
| `lk` (this project) | Deciding what changed, choosing versions, moving pins, running Gradle, uploading, catalogs, BOM. |

The libraries don't use `lk-gradle-helpers`: its publishing convention (POM, signing, Dokka setup) is written into
each repo's root build script.

## Versions: epoch semver, one per module

`(epoch × 1000 + breaks).minor.patch`, e.g. `7003.2.1` = design era 7, third breaking change, second addition,
first fix.

- Every published module has its own `gradle.properties` holding `group=…` and `version=…` (a subproject's file
  overrides the root's).  A single-module repo uses the root file.
- That file is also how the tool finds modules without starting Gradle: a directory whose `gradle.properties` has a
  `version` line is a published module, its artifact is the directory name, and its Gradle path follows its
  directory path.  The tool fails loudly when a published artifact isn't where that predicts.
- The tool is the only thing that edits that line.  A person edits it only to start a new epoch (`8000.0.0`, or
  `lk epoch <repo> 8`) or to declare a break the tool can't see (see "Source breaks").  A written version ahead of
  the last release is released as written.
- Nothing passes `-Pversion`: it would override every module at once.

### Choosing a module's next version

Compared with the module's last release.  The largest applicable bump wins, and the version written in the file is
a floor: the result is whichever is higher, the written version or the computed one.  (A floor, not an override, so
a version left in the file by an interrupted release can't hide a break made afterwards.)

| Cause | Bump |
|---|---|
| ABI lines removed or changed | Major (`breaks + 1`, minor and patch to 0) |
| A direct `api` dependency released a new major | Major |
| ABI lines only added | Minor |
| A direct `implementation` dependency released a new major | Patch (must be rebuilt against it; its users don't see it) |
| Own inputs changed, ABI identical | Patch |
| `--rebuild` | Patch |

A dependency's minor or patch never rebuilds its dependents; apps receive those through the BOM.
The same rules apply to sibling modules, modules in other repos, and third-party libraries, except that a minor of
a `0.x` third-party library counts as a major.

### Source breaks

The ABI dump sees binary breaks.  It cannot see a source-only break (a new sealed subclass, a new abstract member,
an overload that makes calls ambiguous).  For those, hand-edit the version to the next major in the same commit.
Binary-only breaks that happen to recompile cleanly (a new default parameter) still count as a major.

## What makes a module "changed"

1. **Own inputs hash** differs from its last release: the module directory's `build.gradle.kts`, `gradle.properties`
   without the `version` line, and `src/<source set>/**` for source sets not named `*test*`.
2. **A direct dependency moved to a new major** since the versions recorded at its last release.  Current versions
   are the other Lightning Kite modules' current releases and the repo's own `gradle/libs.versions.toml`, matched
   by `group:name`.  The same coordinate at two different versions is an error.

Not release inputs: root build scripts, `buildSrc/`, `settings.gradle.kts`, the wrapper, root `gradle.properties`,
`gradle/libs.versions.toml`.  (When the root project is itself a published module, its `build.gradle.kts` is that
module's input.)  Their combined hash is recorded per release; `lk plan` reports modules built with older build
logic, and `--rebuild <repo|module>` releases them deliberately.

Limitations of the dependency rule:
- It sees dependencies declared through a version catalog.  A version written as a literal in a build script, a
  dependency whose version a BOM supplies, and a KSP processor (not a dependency of the published module at all)
  are caught only as "own inputs changed".
- Native targets publish no runtime variant, so a dependency declared for native targets alone counts as `api`.

## Release record

Stored beside the module's own artifacts, uploaded last so its presence means the release is complete:

- `<artifact>-<version>-release.properties`: `inputsHash`, `buildLogicHash`, `gitCommit`, and each direct dependency
  as `coordinate = version, api|implementation`.
- `<artifact>-<version>-abi.txt`: the concatenated ABI dumps.

Dependencies and their scope are read from the Gradle module metadata (`.module`) the publish just produced: `api`
when a target's api variant lists it, `implementation` when only runtime variants do.

## The tool

Run as `./lk`.  No Gradle starts until a module is known to have changed.

| Command | Does |
|---|---|
| `lk plan [--public]` | Prints every module: unchanged, or old → new version and why.  Leaves no file edited; runs the ABI dump for changed modules, since the bump depends on it. |
| `lk pin <repo>` | Moves a repo's upstream pins to the latest catalogs, for developing against a new upstream minor. |
| `lk release` | **Local, the default.**  Publishes changed modules to mavenLocal as `<next>-local`. |
| `lk release --public` | The real release to LK Maven. |
| `--rebuild <repo\|module>[,…]` | Adds a patch release for modules that are otherwise unchanged.  A name that matches nothing is an error. |
| `--tests [true\|false]` | Public releases run the released modules' tests by default; local ones don't. |
| `lk docs <repo>` | Generates and uploads the repo's Dokka output. |
| `lk epoch <repo> <n>` | Writes `<n>000.0.0` into every module of a repo. |
| `--buckets <directory>` | Rehearsal: `s3://<bucket>` becomes `<directory>/<bucket>`, and every build also resolves from there. |

`LK_GRADLE_ARGS` holds extra arguments for every Gradle run the tool starts (for example `--offline`).

Repos are walked in dependency order: service-abstractions, lightning-server, reactive, kiteui,
lightning-server-kiteui, lightning-bot (own bucket, outside the stack catalog).

### Per repo

1. Hash every module; fetch the release records in batches.  Nothing changed and no dependency major → next repo.
2. If a module was recorded against an older major of another repo's module, move that repo's catalog pin in
   `settings.gradle.kts` to its latest catalog.  This happens before anything is built, so source already adapted
   to the new major compiles.  A pin whose catalog was never published also moves to the latest.
3. Run Gradle once to dump the ABI of the own-changed modules; diff; choose versions in dependency order so bumps
   cascade.
4. Write the new versions into the modules' `gradle.properties`.
5. `--public`: run Gradle once to test and publish exactly those modules, plus the repo catalog, into
   `build/staging-repo` (and into mavenLocal, so the releasing machine can build against them at once); commit ("Release <module> <version>, …", including any pin move); upload artifacts;
   merge each `maven-metadata.xml`; upload the release records.  The tool does not push.
6. Local: run Gradle once with `publishToMavenLocal` for exactly those modules and the catalog; write the release
   records into mavenLocal; restore the version lines and pins.

Failure handling: a failed build restores the files and stops the run.  A failed upload after the commit heals on
rerun: the committed versions are the floor, and each module without a release record is published again.

A real break usually means a downstream repo no longer compiles, so the run stops there with the upstream already
released.  That is safe: the super catalog and BOM are published only at the end of a run in which every module's
recorded dependency majors match the latest releases, so apps never see a half-finished cascade.  Fix the
downstream source (developing against both repos in the metawork composite build) and rerun.

### Local mode

- A dirty tree is fine.  No commits, no S3 writes.
- The bump is always computed against the last **public** release.  Republishing locally any number of times gives
  the same `X-local`, unless the accumulated changes raise the level (a patch becoming a minor or a major).
- Release records are written to mavenLocal too, so a module unchanged since its last local publish is skipped.
- A `-local` build is used only while it is newer than the latest public release, so stale ones drop out by
  themselves.  Gradle orders `X-local` below `X`.
- Each repo with local builds publishes `<repo>-catalog:local`; downstream repos are built with their pins on it.
  The run ends by publishing `stack-catalog:local` and `stack-bom:local`; the app under test points at those and
  has `mavenLocal()`.
- On start, the tool restores any `-local` version or pin left behind by a run that crashed.

`--public` refuses a dirty tree.

## Catalogs and BOM

- **Per-repo catalog** `com.lightningkite.stack:<repo>-catalog`: every module and Gradle plugin of the repo at its
  current version.  Published by Gradle's built-in `version-catalog` plugin from the root script, whenever any
  module of the repo releases, at the version the tool passes as `-PcatalogVersion`.  A catalog's version is an
  aggregate of what moved in it, by the modules' own rules: a module removed or moved to a new major is a major,
  a module added or moved to a new minor is a minor, anything else a patch.  The first is `1000.0.0`.  (Catalogs
  released on 2026-11-11 are named by timestamp instead; any epoch-semver version counts as newer than those.)
- **Third-party versions** stay in each repo's own `gradle/libs.versions.toml`.
- **Super catalog and BOM** `com.lightningkite:stack-catalog` / `stack-bom`: the tool merges the latest per-repo
  catalogs of the five stack repos and publishes both through `../lightning-kite-stack-catalog`, a small plain Gradle project
  (`version-catalog` and `java-platform` plugins).  Libraries are named `<last part of group>-<artifact>`
  (`services-basis`, `lightningserver-core`, `kiteui-library`); the catalog also names its own BOM as `stack-bom`,
  so an app has one version to change.  Their shared version follows the same aggregate rule over every module in
  the stack, and nothing is published when no version moved.
- lightning-bot publishes `com.lightningkite.stack:lightning-bot-catalog` to its own bucket; an app that uses it
  imports that catalog separately.

The catalog gives names; the BOM is what lifts transitive modules to the latest minor and patch in apps.

## What each repo's Gradle looks like

- Per published module: `gradle.properties` with `group=` and `version=`, and in its script only what differs
  (its POM description).
- Root script, once, for every module that applies `com.vanniktech.maven.publish`: POM, signing, Dokka setup,
  Kotlin `abiValidation()` with `checkKotlinAbi` disabled, and the repository
  `maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("staging-repo")) }`.
- Root script: the `version-catalog` block listing the publishing subprojects, published as publication `catalog`.
- `settings.gradle.kts`: each upstream's catalog imported at a pinned version
  (`create("sa") { from("com.lightningkite.stack:service-abstractions-catalog:<pin>") }`).

Consequence: a hand-run `publishToMavenLocal` publishes unreleased code under the last released version.  Local
publishing goes through `lk release`.

## Tests

`lk release --public` runs the tests of every module it is about to release, in the same Gradle run that publishes
to staging and before anything is committed or uploaded.  A failing test aborts the whole run and restores the
files.  `--tests false` skips them.  Local releases run no tests unless `--tests` is given.

"The tests" are what the rolling deploy before this tool ran: the `test` task of modules with a `src/test`
directory, which is the JVM modules.  Kotlin Multiplatform modules' tests (`allTests`: JVM, browser, iOS simulator)
are not part of a release.

The tool's own tests (`./gradlew test`): unit tests for the decision table, the cascade, `0.x` third-party
handling, inputs hashing, pin moves, metadata merge and reading module metadata, and one end-to-end story running
real Gradle against two fixture repos and a directory standing in for the bucket.

## Docs

Dokka generation stays a Gradle task in each repo; `lk docs <repo>` runs it and uploads each module's output to
`<group>/<artifact>/<version>/docs`, with a redirect at `<group>/<artifact>/docs`.

## Pushing

The tool never pushes.  After a public run it lists the repos it committed to and prints each catalog version.

## Later, not built

- Excluding declarations behind an opt-in "unstable" annotation from the ABI dump, so experimental churn isn't a major.
- Detecting some source-only breaks from added ABI lines (new sealed subclass, new abstract member).
- Running Kotlin Multiplatform tests as part of a release.
- CI, remote build cache.
