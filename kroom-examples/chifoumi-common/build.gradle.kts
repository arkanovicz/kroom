description = "Chifoumi Arena - the lobby's declarations, fold and pages, shared by the server and the browser"

// the velocity gradle plugin comes from maven-local like the engine
buildscript {
    repositories { mavenLocal(); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath(libs.velocity.engine.gradle.plugin) }
}

plugins {
    alias(libs.plugins.multiplatform)
}
apply(plugin = "org.apache.velocity.engine")

// The pages, translated per language at build (`<lang>/lobby.html`), then compiled to common classes: the browser
// merges them, the l10n plugin of this repository cannot be applied from within it, so its translator runs as a tool.
val l10n: Configuration by configurations.creating {
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}
dependencies { l10n(project(":kroom-webapp-l10n")) }
val pages = layout.buildDirectory.dir("generated/pages")
val translatePages by tasks.registering(JavaExec::class) {
    classpath = l10n
    mainClass.set("com.republicate.kroom.webapp.l10n.TranslateTreeKt")
    val sources = layout.projectDirectory.dir("src/commonMain/pages")
    val i18n = layout.projectDirectory.dir("src/commonMain/resources/i18n")
    inputs.dir(sources); inputs.dir(i18n); outputs.dir(pages)
    args(sources.asFile.path, i18n.asFile.path, "en", "en,fr", pages.get().asFile.path)
}

configure<org.apache.velocity.engine.gradle.VelocityExtension> {
    property("resource.loader.stock.sources", pages.get().asFile.path)
    property("compat.informal_navigation", true)   // `$state.users` is one reference
    compileTemplates()
}
tasks.named("generateVelocityTemplates") { dependsOn(translatePages) }

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    js {
        browser {
            testTask { enabled = false } // the golden is a file: node reads it, a browser cannot
            commonWebpackConfig { outputFileName = "arena.js" } // the pages' one script: the fold, the pages, the app's main
        }
        nodejs()
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kroom-common"))
            api(project(":kroom-client"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.slf4j.simple) // kson logs through slf4j on the JVM
        }
    }
}
