rootProject.name = "lightning-kite-release-tool"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    }
}
