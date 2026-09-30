package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.core.Mailer
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties

/**
 * The site's SMTP transport over its *Mail* settings, read at each send — an admin fixes a password and the
 * next mail goes. A failing send throws, as a [Mailer] does: the caller decides whether that is the user's
 * problem (a registration code) or only the log's (a notification). What was sent is nobody's business but
 * the recipient's: no log, no webmail — a debugging mailbox (Mailpit) sits beside the demo, not in the product.
 */
internal class SmtpMailer(private val site: Site) : Mailer {

    override suspend fun send(to: String, subject: String, body: String) {
        val settings = site.settings()
        val host = settings["smtpHost"]?.trim().orEmpty()
        check(host.isNotEmpty()) { "mail is not configured" }
        val user = settings["smtpUser"].orEmpty()
        val properties = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", settings["smtpPort"] ?: "587")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            when (settings["smtpSecurity"]) {
                "starttls" -> { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true") }
                "tls" -> put("mail.smtp.ssl.enable", "true")
            }
            if (user.isNotEmpty()) put("mail.smtp.auth", "true")
        }
        val message = MimeMessage(Session.getInstance(properties)).apply {
            setFrom(InternetAddress(settings["mailFrom"]?.takeIf { it.isNotBlank() } ?: "noreply@$host"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            sentDate = Date()
        }
        withContext(Dispatchers.IO) {
            if (user.isNotEmpty()) Transport.send(message, user, settings["smtpPassword"].orEmpty()) else Transport.send(message)
        }
    }
}
