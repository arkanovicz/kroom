description = "Kroom velocity l10n - Gradle plugin emitting per-language, build-time-compiled templates"

plugins {
    alias(libs.plugins.jvm)
    `java-gradle-plugin`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Build-time use of TemplateTranslator + PoParser (and, transitively, the velocity 3.0 parser).
    implementation(project(":kroom-webapp-l10n"))
    // VelocityExtension, to point its templateRoot at the generated, translated trees.
    implementation(libs.velocity.engine.gradle.plugin)
    // KotlinJvmProjectExtension, to wire the generated stubs into the consumer's main source set.
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        create("velocityL10n") {
            id = "com.republicate.kroom.velocity-l10n"
            implementationClass = "com.republicate.kroom.gradle.l10n.VelocityL10nPlugin"
        }
    }
}

// Bake the catalog's velocity version into the plugin, so it injects velocity-engine-common at the
// exact version the K2 plugin compiles against — consumers add nothing, no drift possible.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val velocityVersion = libs.versions.velocity.asProvider().get()
    val outDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("velocityVersion", velocityVersion)
    outputs.dir(outDir)
    doLast {
        outDir.get().file("com/republicate/kroom/gradle/l10n/BuildInfo.kt").asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.republicate.kroom.gradle.l10n

                internal object BuildInfo {
                    const val VELOCITY_VERSION = "$velocityVersion"
                }
                """.trimIndent()
            )
        }
    }
}

kotlin.sourceSets.main { kotlin.srcDir(generateBuildInfo) }
