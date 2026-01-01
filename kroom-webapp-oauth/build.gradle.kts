description = "Kroom webapp oauth - PAC4J-based OAuth plugin"

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
    api(libs.ktor.server.sessions)
    api(libs.ktor.server.auth)
    api(libs.pac4j.core)
    api(libs.pac4j.oidc)
    api(libs.pac4j.oauth)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
