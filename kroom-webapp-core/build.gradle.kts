description = "Kroom webapp core - foundation for Ktor webapps"

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
    api(project(":kroom-server"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.netty)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.server.sse)
    api(libs.ktor.server.status.pages)
    api(libs.ktor.server.call.logging)
    api(libs.ktor.server.default.headers)
    api(libs.essential.kson)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
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
