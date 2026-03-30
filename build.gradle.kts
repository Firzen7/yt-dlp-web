plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("io.ktor.plugin") version "3.4.1"
}

group = "net.firzen.web"
version = "1.9.6"

repositories {
    mavenCentral()
}

application {
    mainClass.set("net.firzen.web.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
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

ktor {
    fatJar {
        archiveFileName.set("yt-dlp-web-v$version.jar")
    }
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
    implementation("io.ktor:ktor-server-sessions:$ktor_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Konfig - simple configuration file API for Kotlin, https://github.com/npryce/konfig, Apache 2.0 license
    implementation("com.natpryce:konfig:1.6.10.0")

    // Bouncy Castle - cryptographic library for scrypt password hashing, MIT license
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // Joda Time
    implementation("joda-time:joda-time:2.14.1")

    // OkHTTP, see: https://github.com/square/okhttp, Apache 2.0 license
    // general network communication
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
