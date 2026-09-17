import com.strumenta.antlrkotlin.gradle.AntlrKotlinTask

description = "Kroom markdown - VTL for Markdown documents, where '#' is a heading and directives move to '%'"

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.antlr)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

// Resolves the engine jar the grammar template is unpacked from — a second view of the same artifact,
// kept apart from the compile classpath so the codegen task declares exactly what it reads.
val grammarTemplate: Configuration by configurations.creating

dependencies {
    api(libs.velocity.engine.common)                    // LexerSource, Config, parse — this module's public surface
    // classic facade: the sub-engine is one (it owns the resource-loader properties an app configures
    // under `markdown.`), and MarkdownDirective IS a classic Directive — both are public surface
    api(libs.velocity.engine.core)
    implementation(libs.velocity.engine.scripting)      // the runtime compiler: markdown templates are authored, hence interpreted
    implementation(libs.antlr.kotlin)
    // md→html, JVM-only by design; nothing of it crosses this module's API
    implementation(libs.flexmark)
    implementation(libs.flexmark.ext.autolink)
    implementation(libs.flexmark.ext.gfm.strikethrough)
    implementation(libs.flexmark.ext.gfm.tasklist)
    implementation(libs.flexmark.ext.tables)
    grammarTemplate(libs.velocity.engine.common)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

// The engine ships its lexer grammar as a TEMPLATE (org/apache/velocity/parser/VelocityLexer.g4 inside
// velocity-engine-common), the three configurable sigils left as placeholders. Substituting them and
// running ANTLR yields a lexer for another dialect; only the LEXER varies, since the parser routes on
// token types, identical across variants. '#' → '%'; '@' and '*' stay — Markdown has no quarrel with them.
val directiveChar = "%"

val markdownGrammar = tasks.register("markdownGrammar") {
    description = "Unpacks the engine's grammar template and substitutes the Markdown-friendly sigils."
    group = "code generation"
    inputs.files(grammarTemplate)
    val outDir = layout.buildDirectory.dir("grammar")
    outputs.dir(outDir)
    doLast {
        val jar = grammarTemplate.files.first { it.name.startsWith("velocity-engine-common") }
        val template = zipTree(jar).matching { include("org/apache/velocity/parser/VelocityLexer.g4") }.singleFile.readText()
        val dir = outDir.get().asFile
        dir.deleteRecursively(); dir.mkdirs()
        dir.resolve("MarkdownLexer.g4").writeText(
            template
                .replace("@@HASH@@", directiveChar)
                .replace("@@AT@@", "@")
                .replace("@@STAR@@", "*")
                .replaceFirst("lexer grammar VelocityLexer;", "lexer grammar MarkdownLexer;")
        )
    }
}

val generateMarkdownLexer = tasks.register<AntlrKotlinTask>("generateMarkdownLexer") {
    antlrClasspath = configurations.detachedConfiguration(
        project.dependencies.create(libs.antlr.kotlin.target.get())
    )
    packageName = "com.republicate.kroom.markdown.parser"
    arguments = listOf("-Dlanguage=Kotlin", "-no-visitor", "-no-listener", "-encoding", "UTF-8")
    source = objects.sourceDirectorySet("antlr", "antlr")
        .srcDir(layout.buildDirectory.dir("grammar")).apply { include("*.g4") }
    outputDirectory = layout.buildDirectory.dir("generated-src/kotlin").get().asFile
    group = "code generation"
    dependsOn(markdownGrammar)
}

kotlin.sourceSets.main { kotlin.srcDir(generateMarkdownLexer) }

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
