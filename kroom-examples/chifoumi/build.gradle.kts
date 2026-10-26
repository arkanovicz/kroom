description = "Chifoumi Arena - Rock-Paper-Scissors-Well multiplayer game"

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.kotlin.serialization) // the session cookie needs a serializer under ktor 3
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.republicate.kroom.examples.chifoumi.MainKt")
}

dependencies {
    implementation(project(":kroom-examples:chifoumi-common")) // the lobby's declarations and fold, the browser's too
    implementation(project(":kroom-webapp-core"))
    implementation(project(":kroom-webapp-assets")) // the stock scripts the pages load from /js/
    implementation(project(":kroom-webapp-velocity"))
    implementation(project(":kroom-webapp-l10n"))
    // implementation(project(":kroom-webapp-oauth"))  // TODO: add later

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.sse)
    runtimeOnly(libs.velocity.engine.scripting) // the example renders its templates interpreted
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}

// The browser's bundle (chifoumi-common's js executable) ships under /js/ with the other static files
val bundle = tasks.getByPath(":kroom-examples:chifoumi-common:jsBrowserDistribution")
tasks.processResources {
    dependsOn(bundle)
    from(bundle.outputs.files) { into("static/js"); include("arena.js", "arena.js.map") }
}
