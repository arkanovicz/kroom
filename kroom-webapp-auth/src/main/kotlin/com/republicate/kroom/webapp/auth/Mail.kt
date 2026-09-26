package com.republicate.kroom.webapp.auth

/**
 * The mail transport, shared with every other module that sends mail (authoring's plugins); a failing send
 * answers 502 on the auth routes, the pending code kept for resend.
 */
typealias Mailer = com.republicate.kroom.webapp.core.Mailer

data class MailMessage(val subject: String, val body: String)
