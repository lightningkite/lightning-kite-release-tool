plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

application {
    mainClass.set("com.lightningkite.release.MainKt")
    applicationName = "lk"
}

dependencies {
    // Gradle module metadata (`.module` files) is JSON.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    // The parser Gradle itself reads version catalogs with, so a catalog means the same thing here as in a build.
    implementation("org.tomlj:tomlj:1.1.1")
    // Turns the functions in Main.kt into the command line.
    implementation("com.lightningkite:kotliner-cli:1.0.5")
    // kotliner-cli asks for an older kotlin-reflect; reflection has to match the Kotlin this is compiled with.
    implementation(kotlin("reflect"))
    implementation("software.amazon.awssdk:s3:2.55.5")
    implementation("software.amazon.awssdk:netty-nio-client:2.55.5")
    // The `lk` profile authenticates through `aws login`, which needs this module on the classpath.
    implementation("software.amazon.awssdk:signin:2.55.5")
    // The AWS SDK logs through SLF4J; without a binding every run starts with three lines of warning.
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}