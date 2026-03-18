plugins {
    kotlin("jvm") version "2.2.20"
}

group = "net.firzen.web"
version = "1.0"

repositories {
    mavenCentral()
}

val generateBuildConfig = tasks.register("generateBuildConfig") {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig")
    inputs.property("version", version)
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().file("BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package net.firzen.web

            object BuildConfig {
                const val VERSION = "$version"
            }
            """.trimIndent()
        )
    }
}

sourceSets.main {
    java.srcDir(generateBuildConfig)
}

dependencies {
    testImplementation(kotlin("test"))

    // JSON-java library, https://github.com/stleary/JSON-java
    // License: https://github.com/stleary/JSON-java/blob/master/LICENSE
    // The Software shall be used for Good, not Evil.
    implementation("org.json:json:20231013")

    // Ktor microservices framework, Apache 2.0 license
    val ktor_version = "3.4.1"
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages:$ktor_version")

    // Konfig - simple configuration file API for Kotlin, https://github.com/npryce/konfig, Apache 2.0 license
    implementation("com.natpryce:konfig:1.6.10.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}