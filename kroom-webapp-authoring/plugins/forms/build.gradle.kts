description = "Kroom plugin - forms: a contact form blocks can call, its messages kept, listed and expired"

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
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(project(":kroom-velocity-markdown"))       // the form is called from a `%` block
    testRuntimeOnly(libs.velocity.engine.scripting)       // test pages are interpreted
}

tasks.test {
    useJUnitPlatform()
}
