
rootProject.name = "kroom"

// Core SSE library
include("kroom-common")
include("kroom-view")
include("kroom-server")

// Build tooling
include("kroom-velocity-l10n")

// Webapp framework
include("kroom-webapp-core")
include("kroom-webapp-assets")
include("kroom-webapp-velocity")
include("kroom-webapp-l10n")
include("kroom-webapp-session")
include("kroom-webapp-oauth")
include("kroom-webapp-auth")
include("kroom-webapp-push")

// Rendering
include("kroom-markdown")

// Authoring
include("kroom-webapp-authoring")

// Examples
include("kroom-examples:chifoumi")
project(":kroom-examples:chifoumi").projectDir = file("kroom-examples/chifoumi")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
