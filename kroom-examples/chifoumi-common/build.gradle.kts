description = "Chifoumi Arena - the lobby's declarations and fold, shared by the server and the browser"

plugins {
    alias(libs.plugins.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    js {
        browser { testTask { enabled = false } } // the golden is a file: node reads it, a browser cannot
        nodejs()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kroom-common"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.slf4j.simple) // kson logs through slf4j on the JVM
        }
    }
}
