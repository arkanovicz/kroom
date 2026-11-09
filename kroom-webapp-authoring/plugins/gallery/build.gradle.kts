description = "Kroom plugin - gallery: photo galleries any block can call, pages born as galleries, a grid and a slideshow"

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
    api(project(":kroom-webapp-images"))                 // renditions: the grid's squares, the show's fit — an application hands the Images over
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(project(":kroom-markdown"))       // the gallery is called from a `%` block
    testRuntimeOnly(libs.velocity.engine.scripting)       // test pages are interpreted
}

tasks.test {
    useJUnitPlatform()
}
