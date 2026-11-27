description = "Kroom markdown editor - a markdown text several people write, in the browser: its region kroom/text.html, the writer, the toolbar, the preview"

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
// one engine and `#parse("kroom/text.html")`. For the browser alone: compiled templates publish the settings they
// were built with, and every engine on a JVM classpath takes them. Whoever shares an engine with it builds with the
// same settings. The text's model (TextState) is kroom-common's, for the server.
configure<org.apache.velocity.engine.gradle.VelocityExtension> {
    property("resource.loader.stock.sources", layout.projectDirectory.dir("src/commonMain/pages").asFile.path)
    property("resource.loader.stock.prefix", "kroom")
    property("compat.informal_navigation", true)   // `$state.parts` is one reference
    compileTemplates()
}

kotlin {
    js {
        browser {
            testTask { useKarma { useChromeHeadless() } }
            commonWebpackConfig { outputFileName = "editor.js" }   // the plain-JS pages' one script, kroom-webapp-assets ships it
        }
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kroom-client"))
        }
        jsTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
