package com.lightningkite.release

/** Moves [target]'s upstream catalog pins to the latest released catalogs.  Returns what each settings file now pins. */
fun pinToLatest(workspace: Workspace, target: Repo): Map<String, Map<String, String>> =
    target.settingsFiles.associate { settings ->
        val text = settings.readText()
        val latest = pins(text).mapValues { (upstream, pinned) ->
            val upstreamRepo = workspace.repos.singleOrNull { it.name == upstream } ?: throw UserError("$settings pins $upstream, which isn't one of the repos")
            workspace.open(upstreamRepo.bucket, forWriting = false).use { bucket ->
                latestCatalog(bucket.read("${catalogDirectory(upstream)}/maven-metadata.xml")?.let { mavenMetadataVersions(it) }.orEmpty()) ?: pinned
            }
        }
        settings.writeText(withPins(text, latest))
        settings.relativeTo(target.directory).path to latest
    }

/**
 * Uploads the Dokka HTML Gradle generated for [module] to `<group>/<artifact>/<version>/docs`, with a redirect to
 * it at `<group>/<artifact>/docs`.  Returns the address of the docs.
 */
fun uploadDocs(bucket: MavenRepository, module: Module): String {
    val html = module.directory.resolve("build/dokka/html")
    check(html.isDirectory) { "$html is missing; did Dokka run for ${module.gradlePath}?" }
    val versioned = "${module.mavenDirectory}/${module.written}/docs"
    val index = "${bucket.url}/$versioned/index.html"
    bucket.upload(html.walkTopDown().filter { it.isFile }.associateBy { "$versioned/${it.relativeTo(html).invariantSeparatorsPath}" })
    bucket.put(
        mapOf(
            "${module.mavenDirectory}/docs/index.html" to """
                <!DOCTYPE HTML>
                <html lang="en-US">
                <head>
                    <meta charset="UTF-8">
                    <meta http-equiv="refresh" content="0; url=$index">
                    <title>Page Redirection</title>
                </head>
                <body>If you are not redirected automatically, follow this <a href='$index'>link</a>.</body>
                </html>
            """.trimIndent()
        )
    )
    return index
}
