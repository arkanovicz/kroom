description = "Chifoumi Arena - Rock-Paper-Scissors-Well multiplayer game"

plugins {
    alias(libs.plugins.jvm)
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
    implementation(project(":kroom-webapp-velocity"))
    implementation(project(":kroom-webapp-l10n"))
    // implementation(project(":kroom-webapp-oauth"))  // TODO: add later

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.sse)
    implementation(libs.slf4j.simple)
}
