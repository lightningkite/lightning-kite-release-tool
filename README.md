# Lightning Kite release tool

`./lk` releases the six Lightning Kite library repos (service-abstractions, lightning-server, reactive, kiteui,
lightning-server-kiteui, lightning-bot), which it expects checked out next to this project.  It decides what
changed, chooses versions, and starts Gradle only for the modules that really changed.  The design is in
[RELEASE-SPEC.md](RELEASE-SPEC.md).

`../lightning-kite-stack-catalog` is the small Gradle project the tool uses to publish the super catalog and BOM; it must be
checked out next to this project too.

## Commands

| | |
|---|---|
| `./lk plan` | What a local release would publish, and why.  `--public` for the real one. |
| `./lk release` | Changed modules into mavenLocal as `<next>-local`, plus `stack-catalog:local` and `stack-bom:local`.  Point an app's catalog version at `local` to try them. |
| `./lk release --public` | The real release: tests, uploads, commits the version and pin changes.  Never pushes.  The same versions also land in your mavenLocal. |
| `./lk release --rebuild <repos or modules>` | Releases something unchanged, e.g. after a build-logic change or a third-party version change within the same major.  Several names are comma-separated. |
| `./lk pin <repo>`, `./lk epoch <repo> <n>`, `./lk docs <repo>` | Move pins to the latest catalogs; start a new epoch; upload Dokka. |

`./lk` alone lists the commands; `./lk <command> --help` shows one.

## A public release

1. Refresh the `lk` AWS profile (`aws sso login --profile lk`, or however you normally do; it needs write access to
   `lightningkite-maven` and `lightning-bot-maven`).  The tool stops at once without it.
2. `./lk plan --public`, then `./lk release --public`.  The tests are the JVM modules' `test` tasks; if one fails
   the run stops with nothing of that repo committed or uploaded, and a rerun continues from that repo.
   `--tests false` skips them.
3. Push the repos the tool lists under "Release commits to push".
4. Put the printed `stack-catalog` version (and `lightning-bot-catalog`, where used) into the apps'
   `settings.gradle.kts`.

A module that was never released goes out at the version written in its `gradle.properties`.

## Rules worth knowing

A module's version is written in its own `gradle.properties` and only `lk` edits it, except to declare a break the
ABI dump can't see: then write the next major by hand in the same commit.  Never run `publishToMavenLocal` by hand:
it would publish unreleased code under the last released version.

To fix a downstream repo against an upstream's new major, develop against both in the metawork composite build,
then release again.

## Working on the tool

To rehearse without S3, add `--buckets <directory>` to any command: `s3://<bucket>` becomes `<directory>/<bucket>`.
`LK_GRADLE_ARGS` adds arguments to every Gradle run the tool starts (`--offline`, for instance).  The `⏱` lines on
stderr time each step; a run with nothing changed should take a few seconds.

`./gradlew test` runs the tool's tests; the end-to-end one runs real Gradle against two fixture repos and takes a
few minutes.
