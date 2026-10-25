description = "Events v4 spike 2 - kroom-client's Room over a compiled page, on the JVM and on node over jsdom (private/eventsv4.md §9): an app's shape, since a module compiling pages owns the templates registry"

// the velocity gradle plugin comes from maven-local like the engine; a spike takes it on its own buildscript
buildscript {
    repositories { mavenLocal(); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath(libs.velocity.engine.gradle.plugin) }
}

plugins {
    alias(libs.plugins.multiplatform)
}
apply(plugin = "org.apache.velocity.engine")

configure<org.apache.velocity.engine.gradle.VelocityExtension> {
    property("resource.loader.stock.sources", "src/commonMain/resources/vtl")
    property("compat.informal_navigation", true)   // `$state.users` is one reference in the spike's templates
    compileTemplates()                              // page.vtl → velocity.templates.page_vtl, common Kotlin: the browser has no runtime compiler
}

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    js(IR) {
        nodejs()   // the DOM half runs on node with jsdom
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kroom-client"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.velocity.engine.scripting)   // interpreted rendering on the JVM, beside the compiled class
            runtimeOnly(libs.slf4j.simple)
        }
        jsTest.dependencies {
            implementation(npm("jsdom", "25.0.1"))
        }
    }
}
