description = "Kroom webapp velocity - Velocity templating plugin"

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
    api(project(":kroom-webapp-core"))
    api(project(":kroom-common"))              // PathTemplate: placeholder pages and content blocks share it
    api(libs.velocity.engine.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // tests render loader-served (interpreted) templates; prod uses compiled classes, scripting droppable
    testRuntimeOnly(libs.velocity.engine.scripting)
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
