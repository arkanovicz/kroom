description = "Events v3 spike 2 - the Binding built by a DOM sink while velocity merges (private/eventsv3.md §9)"

plugins {
    alias(libs.plugins.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kroom-common"))
            api(libs.velocity.engine.common)   // the sink hangs on the engine's seams: insertion handler, native block macros
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.velocity.engine.scripting)   // interpreted rendering on the JVM
            runtimeOnly(libs.slf4j.simple)
        }
    }
}
