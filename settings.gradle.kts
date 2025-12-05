
rootProject.name = "kroom"

// Core SSE library
include("kroom-common")
include("kroom-view")
include("kroom-server")

// Webapp framework
include("kroom-webapp-core")
include("kroom-webapp-velocity")
include("kroom-webapp-l10n")
include("kroom-webapp-oauth")

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
