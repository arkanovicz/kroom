plugins {
    alias(libs.plugins.jvm)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    // kroom and its velocity 3.0 engine are published here; everything else is on Maven Central
    maven("https://republicate.com/maven2")
    mavenCentral()
}

application {
    mainClass.set("{{package}}.MainKt")
}

dependencies {
    implementation(libs.kroom.webapp.authoring)
    implementation(libs.kroom.markdown)                 // the blocks
    implementation(libs.kroom.plugin.webmaster)
{{#forms}}    implementation(libs.kroom.plugin.forms)
{{/forms}}    implementation(libs.ktor.server.netty)
    runtimeOnly(libs.velocity.engine.scripting)         // pages and blocks are interpreted, edited live
    runtimeOnly(libs.slf4j.simple)
}
