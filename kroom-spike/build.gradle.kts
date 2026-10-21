description = "Events v3 spike - a keyed field over JSON and over the DOM, the page of spec §6.2 as the compiler would emit it"

plugins {
    alias(libs.plugins.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    js {
        browser {
            testTask { enabled = false } // the DOM half runs in the jsdom harness (src/test/js/spike.sh)
            commonWebpackConfig { outputFileName = "spike.js" }
        }
        binaries.executable()
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
