package com.republicate.kroom.webapp.authoring.starter

import java.nio.file.Path

/**
 * The questions, then the site: `java -jar kroom-webapp-authoring-starter-<version>-all.jar [where]` — what
 * republicate.com/kroom/create.sh runs in a bare JRE container, as you, in the current directory.
 */
fun main(args: Array<String>) {
    val into = Path.of(args.firstOrNull() ?: ".")
    println("\n  A kroom site. Press Enter to take what is in brackets.\n")
    val name = ask("Site name", "My site")
    val folder = ask("Folder", Answers.slug(name)).let { Answers.slug(it) }
    val lang = ask("Language", "en").trim().lowercase()
    val languages = ask("Other languages, comma-separated", "").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != lang }
    val contact = ask("Contact email (empty: no contact form)", "").trim().ifEmpty { null }
    val port = ask("Port", "8080").trim().toIntOrNull() ?: 8080
    val answers = Answers(name = name, folder = folder, lang = lang, languages = languages, contact = contact, port = port)
    val written = Starter(answers).write(into)
    println("\n  ${written.size} files written under ${into.resolve(folder)}\n")
    println("""
      cd $folder
      ./run.sh                          # builds, then http://localhost:$port/ — log in as admin, password in .env
      ./gradlew run                     # or, with a JDK 21: set -a; . ./.env; ./gradlew run
    """.trimIndent().prependIndent("  "))
    println()
}

private fun ask(question: String, default: String): String {
    print("  $question [$default]: ")
    System.out.flush()
    val answer = readlnOrNull()?.trim()
    return if (answer.isNullOrEmpty()) default else answer
}
