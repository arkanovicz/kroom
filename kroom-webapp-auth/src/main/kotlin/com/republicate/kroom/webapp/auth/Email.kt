package com.republicate.kroom.webapp.auth

/**
 * Identity key: lowercase + trim only. `+tag` variants stay **distinct** so several
 * people can share one mailbox (`foo+a@x` ≠ `foo+b@x`); rationing them is app policy
 * (see [emailBase]).
 */
fun normalizeEmail(email: String): String = email.trim().lowercase()

/**
 * Quota-grouping key: the normalized address with any `+tag` stripped from the local
 * part (`foo+a@x.com` → `foo@x.com`). For counting variants per mailbox in an app's
 * `createPrincipal`; never the identity key.
 */
fun emailBase(email: String): String {
    val n = normalizeEmail(email)
    val at = n.indexOf('@')
    if (at <= 0) return n
    val local = n.substring(0, at)
    val plus = local.indexOf('+')
    val baseLocal = if (plus >= 0) local.substring(0, plus) else local
    return baseLocal + n.substring(at)
}
