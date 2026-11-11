// `lk --buckets <directory>` passes this to every build of a rehearsal, so each repo resolves what the repos
// before it "released" into the directory standing in for the buckets.
// A system property, because project properties don't reach a repo's buildSrc build.
val buckets = File(System.getProperty("lk.rehearsalBuckets")).listFiles().orEmpty().filter { it.isDirectory }

settingsEvaluated {
    // A build that lists no plugin repositories (a buildSrc, usually) gets the plugin portal by default, and would
    // lose it the moment any repository is listed.
    if (pluginManagement.repositories.isEmpty()) pluginManagement.repositories.gradlePluginPortal()
    for (bucket in buckets) {
        pluginManagement.repositories.maven(bucket)
        dependencyResolutionManagement.repositories.maven(bucket)
    }
}

// Only where a project lists its own repositories: adding one to a project that has none would make Gradle
// ignore the settings' repositories for it.
projectsEvaluated {
    allprojects {
        if (repositories.isNotEmpty()) for (bucket in buckets) repositories.maven(bucket)
    }
}
