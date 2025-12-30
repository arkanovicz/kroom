description = "Kroom webapp assets - shared JS/CSS for Ktor webapps"

plugins {
    alias(libs.plugins.jvm)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

// Generate version constant from project.version (single source of truth)
val generatedSrcDir = layout.buildDirectory.dir("generated/src/main/kotlin")

val generateVersion by tasks.registering {
    val outputDir = generatedSrcDir.get().asFile
    val versionFile = File(outputDir, "com/republicate/kroom/webapp/assets/Version.kt")
    outputs.file(versionFile)
    doLast {
        versionFile.parentFile.mkdirs()
        versionFile.writeText("""
            package com.republicate.kroom.webapp.assets

            /** Generated from project.version - do not edit */
            const val KROOM_VERSION = "${project.version}"
        """.trimIndent() + "\n")
    }
}

sourceSets.main {
    kotlin.srcDir(generatedSrcDir)
}

tasks.named("compileKotlin") {
    dependsOn(generateVersion)
}

dependencies {
    api(libs.ktor.server.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
