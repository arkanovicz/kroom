description = "Kroom webapp l10n - Localization plugin with PO file support"

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
    api(project(":kroom-webapp-velocity"))
    // Inert unless localeStrategy = SESSION; keeps session an implementation detail
    implementation(project(":kroom-webapp-session"))
    // 3.0 common front-end: parse() + the source-ranged AST, for build-time TemplateTranslator
    implementation(libs.velocity.engine.common)
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
