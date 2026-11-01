import com.vanniktech.maven.publish.MavenPublishBaseExtension

description = "Kroom - Kotlin multiplatform library for real-time collaborative applications using Server-Sent Events (SSE)"

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.jvm) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.versions)
}

allprojects {

    group = "com.republicate.kroom"
    version = "0.23-kmp-velocity-SNAPSHOT"

    repositories {
        google()
        mavenCentral()
        mavenLocal() // last: only the locally-published velocity BETA falls through here
    }
}

// run, never consumed
val unpublished = setOf("kroom-webapp-authoring-demo", "kroom-examples", "chifoumi", "chifoumi-common")

subprojects {
    if (name in unpublished) return@subprojects

    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "signing")

    // the publish plugin picks the platform (jvm, multiplatform, gradle plugin), builds the sources
    // and Dokka javadoc jars and the publications; only the pom is ours
    configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        // only the Central upload needs signatures; publishToMavenLocal feeds snapshots and scratch consumers
        if (gradle.startParameter.taskNames.any { it.contains("MavenCentral") }) signAllPublications()
        pom {
            name.set(project.name)
            description.set(project.description)
            url.set("https://github.com/arkanovicz/kroom")
            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("cbrisson")
                    name.set("Claude Brisson")
                    email.set("claude.brisson@gmail.com")
                    organization.set("republicate.com")
                    organizationUrl.set("https://republicate.com")
                }
            }
            scm {
                connection.set("scm:scm:git@github.com/arkanovicz/kroom.git")
                url.set("https://github.com/arkanovicz/kroom")
            }
        }
    }

    configure<SigningExtension> { useGpgCmd() }
}
