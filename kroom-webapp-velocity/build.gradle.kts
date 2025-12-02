description = "Kroom webapp velocity - Velocity templating plugin"

plugins {
    alias(libs.plugins.jvm)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api(project(":kroom-webapp-core"))
    api(libs.velocity.engine.core)
    api(libs.velocity.tools.generic)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
