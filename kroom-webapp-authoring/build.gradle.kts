description = "Kroom webapp authoring - block-based content editing (locks, content stores, edit API)"

plugins {
    alias(libs.plugins.jvm)
    `java-test-fixtures`          // the dummy theme: every layout of the contract, for this module's tests and the demo
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
    implementation(libs.angus.mail)                      // the site's SMTP transport
    testImplementation(libs.greenmail)                   // …tested against an in-process server
    testImplementation(project(":kroom-markdown"))       // the tests render real `%` blocks
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

// the fixtures are for the tests and the demo, not for the artifact
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
