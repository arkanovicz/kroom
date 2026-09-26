package com.republicate.kroom.webapp.core

/**
 * App-supplied mail transport: kroom formats the mails it sends and awaits the send; the app owns SMTP (or
 * an API, or a queue). Throwing reports the failure to the caller; an app preferring fire-and-forget can
 * launch internally and return at once.
 */
fun interface Mailer {
    suspend fun send(to: String, subject: String, body: String)
}
