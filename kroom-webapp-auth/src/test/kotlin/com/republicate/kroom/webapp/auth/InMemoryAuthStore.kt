package com.republicate.kroom.webapp.auth

/** Minimal in-memory [AuthStore] for tests, keyed on Int ids. */
class InMemoryAuthStore : AuthStore<Int> {
    private val principals = mutableMapOf<Int, Principal<Int>>()
    private val byEmail = mutableMapOf<String, Int>()
    private val credentials = mutableMapOf<Pair<Int, String>, Credential>()
    var touched = 0
        private set
    private var seq = 0

    override suspend fun findByNormalizedEmail(email: String): Principal<Int>? =
        byEmail[email]?.let { principals[it] }

    override suspend fun findCredential(id: Int, service: String): Credential? =
        credentials[id to service]

    override suspend fun createPrincipal(email: String?, displayName: String): Principal<Int> {
        val id = ++seq
        val principal = Principal(id, email, displayName)
        principals[id] = principal
        if (email != null) byEmail[email] = id
        return principal
    }

    override suspend fun createCredential(id: Int, service: String, passwordHash: String?, oauthId: String?) {
        credentials[id to service] = Credential(service, passwordHash, oauthId)
    }

    override suspend fun touch(id: Int) {
        touched++
    }
}
