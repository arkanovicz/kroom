# kroom-webapp-auth

Email+password identity for Ktor webapps, with OIDC account linking. argon2id
hashing (BouncyCastle, pure-JVM). Builds on `kroom-webapp-session`; the app owns
its account/credentials schema through an `AuthStore`.

## Usage

```kotlin
fun Application.module() {
    installSessions { sessionSecret = System.getenv("SESSION_SECRET") }

    installAuth<Int> {                  // <Int> = your principal id type
        authStore = MyAuthStore         // your skorm-backed implementation
        pepper    = System.getenv("PASSWORD_PEPPER")   // optional
    }

    // OAuth that links into the same accounts by email:
    installOAuth {
        googleClientId = ...; googleClientSecret = ...
        onAuthenticated = { session, _ -> MyAuthStore.linkOidc(session) }
    }
}
```

## Routes

- `POST /api/auth/register` — `{email, password, displayName}`: creates a
  principal + `password` credential, sets the session
- `POST /api/auth/login` — `{email, password}` (email only; display-name login
  waits until name-uniqueness lands)
- `POST /api/auth/logout`

## AuthStore

The app implements persistence — the module never owns tables:

```kotlin
interface AuthStore<ID> {
  suspend fun findByNormalizedEmail(email: String): Principal<ID>?
  suspend fun findCredential(id: ID, service: String): Credential?
  suspend fun createPrincipal(email: String?, displayName: String): Principal<ID>
  suspend fun createCredential(id: ID, service: String, passwordHash: String?, oauthId: String?)
  suspend fun touch(id: ID)
}
```

- Map your rows into `Principal<ID>(id, email, displayName)` /
  `Credential(service, passwordHash, oauthId)` — plain value projections.
- Throw `AuthStoreException` from `createPrincipal` to reject under your own
  policy (e.g. an email-variant quota); `register` returns a clean 4xx.
- `ID` is your native key: `installAuth<Int>` / `<Long>` / `<String>` / `<Uuid>`
  parse out of the box; supply `idFromString` for anything else.
  `call.authId<Int>()` reads it back from the session.

## Email

- `normalizeEmail` = lowercase + trim. `+tag` variants are **distinct**
  identities (`foo+a@x` ≠ `foo+b@x`) so several people can share one mailbox.
- `emailBase` strips the `+tag` — use it inside your `createPrincipal` to ration
  variants per mailbox.

## Hashing

argon2id at the OWASP baseline (19 MiB, t=2, p=1), per-hash random salt,
self-describing PHC strings (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`) so cost can
rise without breaking existing hashes. Optional `pepper` is folded in via
argon2's secret parameter (never stored in the hash).
