description = "Kroom plugin - scratchpad, the page: the text's region and its writer, bundled for the browser"

// the velocity gradle plugin comes from maven-local like the engine
buildscript {
    repositories { mavenLocal(); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath(libs.velocity.engine.gradle.plugin) }
}

plugins {
    alias(libs.plugins.multiplatform)
}
apply(plugin = "org.apache.velocity.engine")

// The region, compiled to a class the browser merges: no parser ships. A module of its own, for the browser alone:
// compiled templates publish the settings they were built with, and an engine sharing their classpath takes them —
// a site's pages are not to render as this region was built.
configure<org.apache.velocity.engine.gradle.VelocityExtension> {
    property("resource.loader.stock.sources", layout.projectDirectory.dir("src/commonMain/pages").asFile.path)
    property("compat.informal_navigation", true)   // `$state.parts` is one reference
    compileTemplates()
}

kotlin {
    js {
        browser {
            testTask { enabled = false }
            commonWebpackConfig { outputFileName = "scratchpad.js" }   // the page's one script: the fold, the region, the writer
        }
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kroom-common"))
            api(project(":kroom-client"))
        }
    }
}
