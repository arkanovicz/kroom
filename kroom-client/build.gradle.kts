description = "Kroom client - the fold bound to a page: data-k-* attributes over the room's state"

plugins {
    alias(libs.plugins.multiplatform)
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    js {
        browser { testTask { enabled = false } } // the grammar is tested on the JVM and on node; the DOM half in the pages' jsdom harness
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
