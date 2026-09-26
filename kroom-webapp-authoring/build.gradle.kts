description = "Kroom webapp authoring - block-based content editing (locks, content stores, edit API)"

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
    api(project(":kroom-webapp-session"))
    // authoring.js builds on the house stack (domhelper, api) and shares its version for cache busting
    api(project(":kroom-webapp-assets"))
    // a store IS a velocity ResourceLoader: one tree, read and written — and pages get $logged/$authoring
    api(project(":kroom-webapp-velocity"))
    api(libs.velocity.engine.core)
    testImplementation(project(":kroom-markdown"))       // the demo renders real `%` blocks
    testImplementation(libs.ktor.server.netty)           // …and the demo app serves them
    // …with the example plugins installed (they depend on this module's main, not its tests: no cycle)
    listOf("webmaster", "forms", "analytics", "webhook", "mail").forEach { testImplementation(project(":kroom-plugin-$it")) }
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // blocks are authored content: interpreted, hence the runtime compiler — in tests only, as elsewhere
    testRuntimeOnly(libs.velocity.engine.scripting)
}

tasks.test {
    useJUnitPlatform()
}

// the demo lives with the tests: it is the same flow, with a browser instead of assertions
tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run the authoring demo on http://localhost:8080"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.republicate.kroom.webapp.authoring.DemoAppKt")
    systemProperty("port", project.findProperty("port") ?: "8088")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
