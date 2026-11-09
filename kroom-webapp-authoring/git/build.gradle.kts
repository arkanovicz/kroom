description = "Kroom webapp authoring git - the content store as a git repository (history, journal)"

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
    implementation(libs.jgit)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
