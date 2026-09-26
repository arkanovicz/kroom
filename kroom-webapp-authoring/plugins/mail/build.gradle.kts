description = "Kroom plugin - mail: the site's SMTP transport, a log of what it sent, and a webmail in the admin bar"

plugins {
    alias(libs.plugins.jvm)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

dependencies {
    api(project(":kroom-webapp-authoring"))
    implementation(libs.angus.mail)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.greenmail)                    // an SMTP server in the test itself
    testRuntimeOnly(libs.velocity.engine.scripting)       // test pages are interpreted
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
