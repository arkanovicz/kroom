package com.republicate.kroom.webapp.authoring.starter

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A site is written whole, pinned to this build, with every hole filled. */
class StarterTest {

    @Test
    fun `a site with a contact — the forms plugin on, the versions this build's, the wrapper ours, no hole left`() {
        val into = Files.createTempDirectory("kroom-starter")
        try {
            val answers = Answers(name = "Les Vagabonds !", lang = "fr", languages = listOf("en"), contact = "club@example.org", port = 9000, uid = 1234, gid = 5678)
            assertEquals("les-vagabonds", answers.folder)
            assertEquals("com.example.les_vagabonds", answers.packageName)
            val written = Starter(answers).write(into)
            val root = into.resolve("les-vagabonds")
            val paths = written.map { it.relativeTo(root).toString() }.toSet()
            for (expected in listOf("build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml", "gradlew", "gradle/wrapper/gradle-wrapper.jar",
                                    "compose.yml", "run.sh", "gradle.properties", ".env", ".gitignore", "README.md",
                                    "src/main/kotlin/com/example/les_vagabonds/Main.kt", "src/main/resources/templates/pages/index.html",
                                    "src/main/resources/templates/pages/login.html")) {
                assertTrue(expected in paths, "$expected missing from $paths")
            }
            written.filter { !it.toString().endsWith(".jar") }.forEach { assertFalse(it.readText().contains("{{"), "a hole left in $it") }
            assertTrue(root.resolve("gradlew").toFile().canExecute() && root.resolve("run.sh").toFile().canExecute())
            assertFalse("Dockerfile" in paths, "nothing to customize in the container: no image of our own")

            val main = root.resolve("src/main/kotlin/com/example/les_vagabonds/Main.kt").readText()
            assertContains(main, "package com.example.les_vagabonds")
            assertContains(main, "import com.republicate.kroom.plugins.forms.Forms")
            assertContains(main, "plugins += listOf(Webmaster(), Forms(), BasicTheme())")
            assertContains(main, """set("notify", "club@example.org")""")
            assertContains(main, """set("lang", "fr")""")
            assertContains(main, """set("languages", "en")""")
            assertContains(root.resolve("build.gradle.kts").readText(), "implementation(libs.kroom.plugin.forms)")

            val versions = java.util.Properties().apply { Starter::class.java.getResourceAsStream("/starter/versions.properties")!!.use { load(it) } }
            val toml = root.resolve("gradle/libs.versions.toml").readText()
            assertContains(toml, "kroom = \"${versions.getProperty("kroom")}\"")
            assertContains(toml, "velocity = \"${versions.getProperty("velocity")}\"")
            assertFalse(versions.getProperty("kroom").contains("$"), "the build filled the versions in")
            val compose = root.resolve("compose.yml").readText()
            assertContains(compose, "\"9000:8080\"")
            assertContains(compose, "image: eclipse-temurin:21-jdk")
            assertContains(compose, "user: \"\${UID}:\${GID}\"")
            assertContains(compose, "- .:\${PWD}")
            assertContains(compose, "./gradlew --no-daemon --console=plain installDist && exec build/install/les-vagabonds/bin/les-vagabonds")
            val env = root.resolve(".env").readText()
            assertContains(env, "UID=1234\nGID=5678\n")
            assertTrue(Regex("ADMIN_PASSWORD=[A-Za-z0-9]{20}").containsMatchIn(env) && Regex("SITE_SECRET=[A-Za-z0-9]{20}").containsMatchIn(env), env)
            assertContains(root.resolve("gradle/wrapper/gradle-wrapper.properties").readText(), "gradle-8.")
        } finally {
            into.toFile().deleteRecursively()
        }
    }

    @Test
    fun `without a contact, no forms — and a taken folder is refused`() {
        val into = Files.createTempDirectory("kroom-starter")
        try {
            Starter(Answers(name = "Plain")).write(into)
            val main = into.resolve("plain/src/main/kotlin/com/example/plain/Main.kt").readText()
            assertFalse(main.contains("Forms"))
            assertContains(main, "plugins += listOf(Webmaster(), BasicTheme())")
            assertFalse(main.contains("languages"))
            kotlin.test.assertFailsWith<IllegalArgumentException> { Starter(Answers(name = "Plain")).write(into) }
        } finally {
            into.toFile().deleteRecursively()
        }
    }
}
