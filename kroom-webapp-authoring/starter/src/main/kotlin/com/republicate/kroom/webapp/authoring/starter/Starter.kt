package com.republicate.kroom.webapp.authoring.starter

import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** What a site is told at birth; the rest it learns from its admin, in the bar. */
data class Answers(
    /** Shown in the header and the titles. */
    val name: String,
    /** The folder, the Gradle project, the container: a slug. */
    val folder: String = slug(name),
    val lang: String = "en",
    /** The other languages the site speaks. */
    val languages: List<String> = emptyList(),
    /** Set, the contact form is on and its messages are mailed there. */
    val contact: String? = null,
    val port: Int = 8080,
    /** Who the site's container runs as: the one who asked for it, so what it writes under `data/` is theirs. */
    val uid: Int = hostId("HOST_UID") { it.uid },
    val gid: Int = hostId("HOST_GID") { it.gid },
    val packageName: String = "com.example." + folder.replace('-', '_').let { if (it.first().isDigit()) "_$it" else it }
) {
    companion object {
        /** create.sh hands the host's ids in; run bare, the JVM's own user stands for them. */
        private fun hostId(variable: String, own: (com.sun.security.auth.module.UnixSystem) -> Long): Int =
            System.getenv(variable)?.toIntOrNull() ?: runCatching { own(com.sun.security.auth.module.UnixSystem()).toInt() }.getOrDefault(1000)

        /** `Notre Société !` → `notre-societe`. */
        fun slug(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }
    }
}

/**
 * Writes a site from the templatized files under `starter/site` and this repository's Gradle wrapper, both
 * carried in the jar: `{{key}}` is filled, `{{#flag}}…{{/flag}}` kept or dropped, `__package__` in a path
 * becomes the package's. The versions pinned are the build's that made this jar ([versions]).
 */
class Starter(private val answers: Answers) {

    private val versions: Properties = Properties().apply {
        Starter::class.java.getResourceAsStream("/starter/versions.properties")!!.use { load(it) }
    }

    private val values: Map<String, String> = mapOf(
        "site" to answers.folder,
        "name" to answers.name,
        "package" to answers.packageName,
        "packagePath" to answers.packageName.replace('.', '/'),
        "lang" to answers.lang,
        "languages" to answers.languages.joinToString(", "),
        "contact" to answers.contact.orEmpty(),
        "port" to answers.port.toString(),
        "uid" to answers.uid.toString(),
        "gid" to answers.gid.toString(),
        "adminPassword" to random(),
        "siteSecret" to random(),
        "kroomVersion" to versions.getProperty("kroom"),
        "velocityVersion" to versions.getProperty("velocity"),
        "ktorVersion" to versions.getProperty("ktor"),
        "kotlinVersion" to versions.getProperty("kotlin"),
        "slf4jVersion" to versions.getProperty("slf4j")
    )

    private val flags: Map<String, Boolean> = mapOf(
        "forms" to !answers.contact.isNullOrBlank(),
        "languages" to answers.languages.isNotEmpty()
    )

    /** The site, under [into]`/<folder>`; answers the files written. */
    fun write(into: Path): List<Path> {
        val root = into.resolve(answers.folder)
        require(!root.exists() || Files.list(root).use { it.findAny().isEmpty }) { "$root exists and is not empty" }
        val written = ArrayList<Path>()
        for ((relative, bytes) in resources("starter/site")) {
            // `_gitignore`: a `.gitignore` would not survive Gradle's default resource excludes on the way into the jar
            val target = root.resolve(relative.replace("__package__", values["packagePath"]!!).replace("_gitignore", ".gitignore"))
            target.parent.createDirectories()
            if (relative.endsWith(".jar")) target.writeBytes(bytes) else target.writeText(fill(String(bytes, Charsets.UTF_8)))
            if (relative.endsWith(".sh")) target.toFile().setExecutable(true)
            written.add(target)
        }
        for ((relative, bytes) in resources("starter/wrapper")) {
            val target = root.resolve(relative)
            target.parent.createDirectories()
            target.writeBytes(bytes)
            if (relative == "gradlew") target.toFile().setExecutable(true)
            written.add(target)
        }
        return written
    }

    /** `{{#flag}}…{{/flag}}` kept when the flag is on, else dropped; then every `{{key}}`. */
    internal fun fill(text: String): String {
        var out = text
        for ((flag, on) in flags) {
            out = Regex("\\{\\{#$flag}}(.*?)\\{\\{/$flag}}", RegexOption.DOT_MATCHES_ALL).replace(out) { if (on) it.groupValues[1] else "" }
        }
        return Regex("\\{\\{(\\w+)}}").replace(out) { values[it.groupValues[1]] ?: error("no value for {{${it.groupValues[1]}}}") }
    }

    /** Every file under a resource folder, as (path under it, bytes) — from a jar or from a classes directory alike. */
    private fun resources(folder: String): List<Pair<String, ByteArray>> {
        val url = Starter::class.java.classLoader.getResource(folder) ?: error("no resources at $folder")
        return when (url.protocol) {
            "jar" -> {
                val jar = (url.openConnection() as JarURLConnection).jarFile
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("$folder/") }
                    .map { it.name.removePrefix("$folder/") to jar.getInputStream(it).use { s -> s.readBytes() } }
                    .toList()
            }
            "file" -> {
                val base = File(url.toURI()).toPath()
                base.walk().filter { it.isRegularFile() }
                    .map { it.relativeTo(base).toString().replace(File.separatorChar, '/') to Files.readAllBytes(it) }
                    .toList()
            }
            else -> error("resources at $url cannot be listed")
        }.sortedBy { it.first }
    }

    private fun random(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rng = SecureRandom()
        return (1..20).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
    }
}
