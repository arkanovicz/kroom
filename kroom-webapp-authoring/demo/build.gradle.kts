description = "Kroom demo - the authoring stack as a thing you can click"

plugins {
    alias(libs.plugins.jvm)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.republicate.kroom.demo.DemoKt")
}

dependencies {
    implementation(project(":kroom-webapp-authoring"))
    implementation(project(":kroom-markdown"))                          // the blocks
    implementation(project(":kroom-plugin-webmaster"))
    implementation(project(":kroom-plugin-forms"))
    implementation(testFixtures(project(":kroom-webapp-authoring")))   // the dummy theme, beside the basic one
    implementation(libs.ktor.server.netty)
    runtimeOnly(libs.velocity.engine.scripting)                         // pages and blocks are interpreted here
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.named<JavaExec>("run") {
    systemProperty("port", project.findProperty("port") ?: "8088")
}

tasks.test {
    useJUnitPlatform()
}
