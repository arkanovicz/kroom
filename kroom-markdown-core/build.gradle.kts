description = "Kroom markdown core - markdown to sanitized html: html stays, scripting goes"

plugins {
    alias(libs.plugins.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // md→html, JVM-only by design; nothing of it crosses this module's API
    implementation(libs.flexmark)
    implementation(libs.flexmark.ext.autolink)
    implementation(libs.flexmark.ext.gfm.strikethrough)
    implementation(libs.flexmark.ext.gfm.tasklist)
    implementation(libs.flexmark.ext.tables)
    api(libs.jsoup)                                     // the sanitizer's allowlist; `Element` is in Markdown.html's hooks
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
