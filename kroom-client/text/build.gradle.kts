description = "Kroom client - a text written by several, its region: kroom/text.html, compiled for the browser"

// the velocity gradle plugin comes from maven-local like the engine
buildscript {
    repositories { mavenLocal(); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath(libs.velocity.engine.gradle.plugin) }
}

plugins {
    alias(libs.plugins.multiplatform)
}
apply(plugin = "org.apache.velocity.engine")

// The region as a class a page's engine merges, under kroom's prefix: an application's own regions sit beside it on
// one engine and `#parse("kroom/text.html")`. For the browser alone, and apart from kroom-client: compiled templates
// publish the settings they were built with, and every engine on a JVM classpath takes them. Whoever shares an
// engine with it builds with the same settings.
configure<org.apache.velocity.engine.gradle.VelocityExtension> {
    property("resource.loader.stock.sources", layout.projectDirectory.dir("src/commonMain/pages").asFile.path)
    property("resource.loader.stock.prefix", "kroom")
    property("compat.informal_navigation", true)   // `$state.parts` is one reference
    compileTemplates()
}

kotlin {
    js {
        browser { testTask { enabled = false } }   // the writer's tests are kroom-client's; the region is driven in the scratchpad
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kroom-client"))
        }
    }
}
