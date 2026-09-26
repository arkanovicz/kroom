package com.republicate.kroom.plugins.mail

import com.republicate.kroom.webapp.authoring.AdminEntry
import com.republicate.kroom.webapp.authoring.Permissions
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Setting.Type.CHOICE
import com.republicate.kroom.webapp.authoring.Setting.Type.NUMBER
import com.republicate.kroom.webapp.authoring.Setting.Type.SECRET
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.core.Mailer
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Date
import java.util.Properties
import kotlin.time.Duration.Companion.days

/**
 * The site's mail transport: sets [Site.mailer] to an SMTP client over its settings, read at each send — an
 * admin fixes a password and the next mail goes. Every send is logged, sent or failed, in the admin bar (the
 * operator sees what left, and why not), for [logDays]. A failing send throws, as a [Mailer] does: the caller
 * decides whether that is the user's problem (a registration code) or only the log's (a notification).
 *
 * `inbox` names a webmail to show in the admin bar — the demo's Mailpit, a hosted mailbox — framed through a
 * redirect, so the address stays a setting.
 */
class Mail(private val logDays: Int = 30) : Plugin {
    override val id = "mail"
    override val name = "Mail"
    override val description = "The site's SMTP transport, what it sent, and a webmail"
    override val settings = listOf(
        Setting("host", "SMTP host", help = "Nothing is sent while empty"),
        Setting("port", "SMTP port", default = "587", type = NUMBER),
        Setting("security", "Security", default = "starttls", type = CHOICE, choices = listOf("starttls", "tls", "none")),
        Setting("username", "User"),
        Setting("password", "Password", type = SECRET),
        Setting("from", "Sender", help = "Site <noreply@example.org>"),
        Setting("inbox", "Webmail", help = "An URL shown in the admin bar — e.g. http://localhost:8025 for Mailpit")
    )

    override fun install(site: Site) {
        val settings = site.settings(this)
        val log = site.records(this, "log")

        site.mailer = Mailer { to, subject, body ->
            val entry = Json.MutableObject().apply { set("time", System.currentTimeMillis()); set("to", to); set("subject", subject) }
            try {
                send(settings, to, subject, body)
                log.add(entry.apply { set("status", "sent") })
            } catch (e: Exception) {
                log.add(entry.apply { set("status", "failed"); set("error", e.message ?: e.javaClass.simpleName) })
                throw e
            }
        }

        site.every(1.days) {
            val cutoff = System.currentTimeMillis() - logDays * 86_400_000L
            log.list(Int.MAX_VALUE).forEach { (id, entry) -> if ((entry.getLong("time") ?: 0) < cutoff) log.delete(id) }
        }

        site.admin(AdminEntry("mail-log", "Sent mail", icon = "M3 11l18-7-7 18-2-8zM12 14l9-10", table = "/api/mail/log"))
        site.admin(AdminEntry("mail-inbox", "Mailbox", icon = "M4 6h16v12H4zM4 7l8 6 8-6", frame = "/mail/inbox"))

        site.routes {
            get("/api/mail/log") {
                if (!site.can(call.userSession, Permissions.ADMIN))
                    return@get respondError("not an administrator", HttpStatusCode.Forbidden, "notAdmin")
                val columns = listOf("time", "to", "subject", "status", "error")
                respondJson {
                    set("columns", Json.MutableArray().apply { columns.forEach { push(it) } })
                    set("rows", Json.MutableArray().apply {
                        log.list(200).values.forEach { entry ->
                            push(Json.MutableArray().apply {
                                columns.forEach { c -> push(if (c == "time") Instant.ofEpochMilli(entry.getLong("time") ?: 0).toString() else entry.getString(c)) }
                            })
                        }
                    })
                }
            }

            get("/mail/inbox") {
                if (!site.can(call.userSession, Permissions.ADMIN)) return@get call.respond(HttpStatusCode.Forbidden)
                val inbox = settings["inbox"]?.trim().orEmpty()
                if (inbox.startsWith("http://") || inbox.startsWith("https://")) call.respondRedirect(inbox)
                else call.respondText("No webmail configured: set it in the mail plugin's settings.", ContentType.Text.Plain)
            }
        }
    }

    private suspend fun send(settings: com.republicate.kroom.webapp.authoring.Settings, to: String, subject: String, body: String) {
        val host = settings["host"]?.trim().orEmpty()
        check(host.isNotEmpty()) { "mail is not configured" }
        val user = settings["username"].orEmpty()
        val properties = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", settings["port"] ?: "587")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            when (settings["security"]) {
                "starttls" -> { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true") }
                "tls" -> put("mail.smtp.ssl.enable", "true")
            }
            if (user.isNotEmpty()) put("mail.smtp.auth", "true")
        }
        val message = MimeMessage(Session.getInstance(properties)).apply {
            setFrom(InternetAddress(settings["from"]?.takeIf { it.isNotBlank() } ?: "noreply@$host"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            sentDate = Date()
        }
        withContext(Dispatchers.IO) {
            if (user.isNotEmpty()) Transport.send(message, user, settings["password"].orEmpty()) else Transport.send(message)
        }
    }
}
