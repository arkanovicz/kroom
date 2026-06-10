package com.republicate.kroom.webapp.auth

/**
 * App-implemented persistence for accounts and credentials.
 *
 * The module owns hashing, normalization, linking and routes; the app owns its
 * schema and maps its rows into [Principal]/[Credential] (plain value projections,
 * not live entities, not references). Ids are the app's native type [ID].
 *
 * [createPrincipal]/[createCredential] may throw [AuthStoreException] to reject a
 * write under app policy (e.g. a per-base-email quota — see [emailBase]); the
 * register route turns it into a clean error response.
 */
interface AuthStore<ID> {
    suspend fun findByNormalizedEmail(email: String): Principal<ID>?
    suspend fun findCredential(id: ID, service: String): Credential?
    suspend fun createPrincipal(email: String?, displayName: String): Principal<ID>
    suspend fun createCredential(id: ID, service: String, passwordHash: String?, oauthId: String?)
    suspend fun touch(id: ID)
}

/** The durable authenticated subject, projected to what the auth module needs. */
data class Principal<ID>(val id: ID, val email: String?, val displayName: String)

/** A login credential for a principal: a `password` hash, or an OIDC link's `oauthId`. */
data class Credential(val service: String, val passwordHash: String?, val oauthId: String?)

/** Thrown by an [AuthStore] write to reject it (quota, policy); mapped to a 4xx. */
class AuthStoreException(message: String) : Exception(message)
