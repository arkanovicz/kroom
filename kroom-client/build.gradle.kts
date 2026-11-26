description = "Kroom client - a page on a room: the stream folded into the State, the page merged into the DOM by the engine's sink after every frame"

plugins {
    alias(libs.plugins.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    js {
        browser { testTask { useKarma { useChromeHeadless() } } }   // jsTest: the DOM half (Intents); the grammar runs here too, and on node
        nodejs { testTask { filter.excludeTestsMatching("*IntentsTest*"); filter.excludeTestsMatching("*PresenceTest*"); filter.excludeTestsMatching("*StreamTest*"); filter.excludeTestsMatching("*WriterTest*"); filter.excludeTestsMatching("*TwoRoomsTest*"); filter.excludeTestsMatching("*BrokenTest*") } }   // no document on node
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
