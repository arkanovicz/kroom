description = "Kroom webapp oauth - OIDC authentication plugin"

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

dependencies {
    api(project(":kroom-webapp-core"))
    api(libs.ktor.server.sessions)
    api(libs.nimbus.oidc.sdk)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
