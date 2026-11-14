description = "Kroom plugin - scratchpad, the page: the browser's entry (the text's region and its writer, the toolbar, the preview), bundled"

plugins {
    alias(libs.plugins.multiplatform)
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
            implementation(project(":kroom-client-text"))   // the region, kroom/text.html
        }
    }
}
