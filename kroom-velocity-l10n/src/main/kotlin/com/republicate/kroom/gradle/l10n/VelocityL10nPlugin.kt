package com.republicate.kroom.gradle.l10n

import org.apache.velocity.engine.gradle.VelocityExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Emits per-language, build-time-compiled Velocity templates.
 *
 * On apply: registers `generateTranslatedTemplates` (translate + stub codegen), applies the velocity
 * K2 plugin and points its `templateRoot` at the generated translated trees, and wires the generated
 * stubs into the consumer's main Kotlin source set. The consumer configures it via [VelocityL10nExtension]
 * and supplies scope instances at render time around the generated `renderPage(path, lang)`.
 */
class VelocityL10nPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("velocityL10n", VelocityL10nExtension::class.java)
        ext.sourceLanguage.convention("en")
        ext.generatedPackage.convention("com.republicate.kroom.l10n.generated")

        val generatedRoot = project.layout.buildDirectory.dir("generated/kroom-l10n")
        val generateTask = project.tasks.register(
            "generateTranslatedTemplates",
            GenerateTranslatedTemplatesTask::class.java
        ) { task ->
            task.templates.set(ext.templates)
            task.i18n.set(ext.i18n)
            task.sourceLanguage.set(ext.sourceLanguage)
            task.languages.set(ext.languages)
            task.appScope.set(ext.appScope)
            task.sessionScope.set(ext.sessionScope)
            task.requestScope.set(ext.requestScope)
            task.generatedPackage.set(ext.generatedPackage)
            task.outputDir.set(generatedRoot)
        }

        // Resolve $ref-bearing templates from the generated, translated trees.
        project.pluginManager.apply("org.apache.velocity.engine")
        project.extensions.getByType(VelocityExtension::class.java)
            .templateRoot(generatedRoot.get().dir("templates").asFile.absolutePath)

        // Generated stubs → main Kotlin source set; the task-output provider wires compile dependsOn generate.
        val generatedKotlin = generateTask.flatMap { it.outputDir.dir("kotlin") }
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType(KotlinJvmProjectExtension::class.java)
                .sourceSets.getByName("main").kotlin.srcDir(generatedKotlin)
        }
    }
}
