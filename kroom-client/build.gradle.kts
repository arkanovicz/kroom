description = "Kroom client - a page on a room: the stream folded into the State, the page merged into the DOM by the engine's sink after every frame"

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
            api(libs.velocity.engine.common)
            api(libs.velocity.engine.dom)   // the DOM sink: the Binding is the engine's, built while the page merges
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.slf4j.simple) // kson logs through slf4j on the JVM
        }
    }
}
