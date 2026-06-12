package com.republicate.kroom.webapp.auth

/**
 * App-supplied mail transport: kroom formats the verification/reset mails and
 * awaits the send; the app owns SMTP (or API, or queue). Throwing reports the
 * failure to the caller (502 on the auth routes, pending code kept for resend);
 * an app preferring fire-and-forget can launch internally and return at once.
 */
fun interface Mailer {
    suspend fun send(to: String, subject: String, body: String)
}

data class MailMessage(val subject: String, val body: String)
