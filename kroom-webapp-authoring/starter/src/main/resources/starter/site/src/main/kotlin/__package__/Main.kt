package {{package}}

import com.republicate.kroom.plugins.webmaster.Webmaster
{{#forms}}import com.republicate.kroom.plugins.forms.Forms
{{/forms}}import com.republicate.kroom.webapp.authoring.BasicTheme
import com.republicate.kroom.webapp.authoring.FileStorage
import com.republicate.kroom.webapp.authoring.MemoryIdentityProvider
import com.republicate.kroom.webapp.authoring.Roles
import com.republicate.kroom.webapp.authoring.installContentSite
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path
import java.security.SecureRandom

/**
 * {{name}} — a kroom site. `./gradlew run`, or `docker compose up`, then http://localhost:{{port}}/
 * Log in as admin with ADMIN_PASSWORD (see .env); the bar on the left is yours.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    println("\n  {{name}} — http://localhost:$port/ (admin)\n")
    embeddedServer(Netty, port = port) { site() }.start(wait = true)
}

fun Application.site() {
    val storage = FileStorage(Path.of("data"))
    // what the site says of itself, the first time; the admin changes it from the bar afterwards
    storage.settings("site").apply {
        if (get("name") == null) set("name", "{{name}}")
        if (get("lang") == null) set("lang", "{{lang}}")
{{#languages}}        if (get("languages") == null) set("languages", "{{languages}}")
{{/languages}}    }
{{#forms}}    storage.settings("forms").apply { if (get("notify") == null) set("notify", "{{contact}}") }
{{/forms}}
    val password = System.getenv("ADMIN_PASSWORD")
        ?: random().also { println("  ADMIN_PASSWORD is not set: this run's admin password is $it\n") }
    installContentSite {
        this.storage = storage
        identity = MemoryIdentityProvider().user("admin", password, Roles.ADMIN)
        loginPage = "pages/login.html"
        sessionSecret = System.getenv("SITE_SECRET") ?: random()
        plugins += listOf(Webmaster(){{#forms}}, Forms(){{/forms}}, BasicTheme())
    }
}

private fun random(): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rng = SecureRandom()
    return (1..20).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
}
