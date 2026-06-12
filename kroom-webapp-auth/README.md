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

        // email verification + password reset (the app owns SMTP)
        mailer = Mailer { to, subject, body -> EmailSender.send(from, to, subject, body) }
    }

    // OAuth that links into the same accounts by email:
    installOAuth {
        googleClientId = ...; googleClientSecret = ...
        onAuthenticated = { session, _ -> MyAuthStore.linkOidc(session) }
    }
}
```

## Routes

- `POST /api/auth/register` — `{email, password, displayName}`: with a `mailer`
  (and `requireVerification`, the default) the registration is **held pending**
  behind an emailed 6-digit code and answers `{pending:true}` — the principal
  only exists after `verify`. Without a mailer: creates the principal +
  `password` credential immediately and sets the session.
- `POST /api/auth/verify` — `{email, code}`: confirms a pending registration
  (or guest upgrade), creates/updates the principal, sets the session.
- `POST /api/auth/resend` — `{email}`: re-issues the pending code (always
  `{ok:true}` — pending status is not leaked).
- `POST /api/auth/login` — `{email, password}` (email only; display-name login
  waits until name-uniqueness lands)
- `POST /api/auth/logout`
- `POST /api/auth/upgrade` — `{email, password}`: attaches email+password to
  the **authenticated** no-email principal (guest upgrade), behind the same
  code flow; the principal id and display name are preserved.
- `POST /api/auth/forgot` — `{email}`: mails a reset code; always `{ok:true}`
  (existence is not leaked).
- `POST /api/auth/reset` — `{email, code, password}`: sets the new password and
  logs in. Also *creates* the password credential if absent, so an OIDC-only
  account can gain one.

`verify`/`resend`/`forgot`/`reset` are only installed when `mailer` is set.

## Verification & codes

- `mailer` — `Mailer { to, subject, body -> ... }`, awaited; throwing answers
  502 and keeps the pending code so `resend` can retry. Fire-and-forget apps
  can launch internally and return at once.
- Mail bodies: override `verifyEmail`/`resetEmail` (`(code) -> MailMessage`).
- Knobs: `requireVerification` (default true, effective only with a mailer —
  a warning is logged otherwise), `codeTtlSeconds` (600), `codeLength` (6),
  `maxVerifyAttempts` (5), `resendCooldownSeconds` (60), `maxMailsPerDay` (5
  per address), `rateLimitPerMinute` (10 per IP across the auth routes, 429
  beyond; 0 disables).
- Codes are compared constant-time and attempt-limited; stores are pluggable
  (`verificationStore`/`resetStore`, `AuthCodeStore` interface) — the in-memory
  default is fine for single-instance deployments (codes are ephemeral; a lost
  code is just re-requested).

## AuthStore

The app implements persistence — the module never owns tables:

```kotlin
interface AuthStore<ID> {
  suspend fun findByNormalizedEmail(email: String): Principal<ID>?
  suspend fun findCredential(id: ID, service: String): Credential?
  suspend fun createPrincipal(email: String?, displayName: String): Principal<ID>
  suspend fun createCredential(id: ID, service: String, passwordHash: String?, oauthId: String?)
  suspend fun setPassword(id: ID, passwordHash: String)  // upsert (reset, upgrade)
  suspend fun setEmail(id: ID, email: String)            // guest upgrade
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
