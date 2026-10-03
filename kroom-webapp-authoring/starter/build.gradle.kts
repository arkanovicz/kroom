description = "Kroom site starter - asks a few questions, writes a site"

plugins {
    alias(libs.plugins.jvm)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // nothing but the standard library: the jar runs alone, in a bare JRE, as `docker run --rm` on any machine
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.processResources {
    // the versions a generated site pins are this build's, and its wrapper is this repository's
    filesMatching("starter/versions.properties") {
        expand("kroom" to project.version, "velocity" to libs.versions.velocity.get(), "ktor" to libs.versions.ktor.get(),
               "kotlin" to libs.versions.kotlin.asProvider().get(), "slf4j" to libs.versions.slf4j.get())
    }
    from(rootDir) {
        include("gradlew", "gradle/wrapper/**")
        into("starter/wrapper")
    }
}

// the one jar create.sh fetches: the starter and the standard library together
val fatJar by tasks.registering(Jar::class) {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.republicate.kroom.webapp.authoring.starter.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
}

tasks.assemble { dependsOn(fatJar) }

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(fatJar)
        }
    }
}
