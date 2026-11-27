description = "Kroom plugin - scratchpad: a text several people write at once, in a page"

plugins {
    alias(libs.plugins.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api(project(":kroom-webapp-authoring"))
    implementation(project(":kroom-markdown-core"))     // the text as html: the preview, and what a page shows of it
}

