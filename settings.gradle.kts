
rootProject.name = "kroom"

// Core SSE library
include("kroom-common")
include("kroom-view")
include("kroom-server")
include("kroom-client")
// a text's region, compiled under the prefix `kroom`, for the browser alone
include("kroom-client-text")
project(":kroom-client-text").projectDir = file("kroom-client/text")

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
include("kroom-webapp-images")

// Rendering
include("kroom-markdown-core")
include("kroom-velocity-markdown")

// Authoring
include("kroom-webapp-authoring")

// Authoring plugins: what a site gains beyond its pages, one artifact each
for (plugin in listOf("webmaster", "forms", "gallery", "scratchpad")) {
    include("kroom-plugin-$plugin")
    project(":kroom-plugin-$plugin").projectDir = file("kroom-webapp-authoring/plugins/$plugin")
}
// the scratchpad's page: the browser's entry, bundled with kroom-client-text's region
include("kroom-plugin-scratchpad-page")
project(":kroom-plugin-scratchpad-page").projectDir = file("kroom-webapp-authoring/plugins/scratchpad/page")

// The git-backed content store: every write a commit, the editor's history a git log (JGit stays out of authoring)
include("kroom-webapp-authoring-git")
project(":kroom-webapp-authoring-git").projectDir = file("kroom-webapp-authoring/git")
// The authoring demo, a thing you can click
include("kroom-webapp-authoring-demo")
project(":kroom-webapp-authoring-demo").projectDir = file("kroom-webapp-authoring/demo")
// The site starter: a jar that asks a few questions and writes a site (republicate.com/kroom/create.sh runs it)
include("kroom-webapp-authoring-starter")
project(":kroom-webapp-authoring-starter").projectDir = file("kroom-webapp-authoring/starter")
// Examples
include("kroom-examples:chifoumi-common")
project(":kroom-examples:chifoumi-common").projectDir = file("kroom-examples/chifoumi-common")
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
