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
}

// The page's bundle (the page module's js executable) ships under /js/ with the plugin's other static files
val bundle = tasks.getByPath(":kroom-plugin-scratchpad-page:jsBrowserDistribution")
tasks.processResources {
    dependsOn(bundle)
    from(bundle.outputs.files) { into("static/js"); include("scratchpad.js", "scratchpad.js.map") }
}
