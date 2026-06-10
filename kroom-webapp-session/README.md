# kroom-webapp-session

Encrypted session identity shared by the kroom authentication modules
(`kroom-webapp-oauth`, `kroom-webapp-auth`). Owns the single Ktor `Sessions`
install, so every cookie type is declared in one place.

## Usage

```kotlin
fun Application.module() {
    installSessions {
        sessionSecret = System.getenv("SESSION_SECRET")
        externalUrl   = "https://example.com"   // public base URL (prod)
        cookieDomain  = ".example.com"           // cross-subdomain SSO (optional)
    }
    // then installOAuth { } and/or installAuth { }
}
```

`installSessions` must be called **before** `installOAuth` / `installAuth`.

## Provides

- `UserSession(id, name, email, provider, appId?)` — the durable authenticated
  subject, carried in an encrypted + signed cookie (`user_session`).
- `AuthFlow` — the transient redirect-login handshake cookie (`auth_flow`), used
  by the OIDC flow.
- `installSessions { sessionSecret; externalUrl; cookieDomain; cookieSecure }`.
- `call.userSession`, `call.isAuthenticated`.
- `validateReturnTo(returnTo, host, cookieDomain)` — open-redirect-safe
  post-login targets (relative, same-host, or under `cookieDomain`).

## Notes

- Cookies are encrypted (AES-128) and signed (HMAC-SHA256) with keys derived
  from `sessionSecret`; `SameSite=Lax`; `secure` defaults to true when
  `externalUrl` is https.
