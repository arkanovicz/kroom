
rootProject.name = "kroom"

include("kroom-common")
include("kroom-view")
include("kroom-server")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
