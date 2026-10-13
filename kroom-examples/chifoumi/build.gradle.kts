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
}
