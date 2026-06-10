# kroom-webapp-oauth

OIDC authentication for Ktor webapps — authorization-code flow over the
encrypted sessions from `kroom-webapp-session`.

## Usage

```kotlin
fun Application.module() {
    installSessions {
        sessionSecret = System.getenv("SESSION_SECRET")

        // single app serving many subdomains behind a reverse proxy
        externalUrl = "https://example.com"
        cookieDomain = ".example.com"
    }

    installOAuth {
        googleClientId = System.getenv("GOOGLE_CLIENT_ID")
        googleClientSecret = System.getenv("GOOGLE_CLIENT_SECRET")

        // enrich or reject logins; returning null rejects
        onAuthenticated = { session, call ->
            session.copy(appId = findOrCreateUser(session))
        }
    }
}
```

`installSessions { }` must be called before `installOAuth { }`.

## Routes

- `GET /oauth/login/{provider}?returnTo=...` — start login (`google`,
  `oidc`, or a custom provider name); `returnTo` defaults to the
  `Referer` and is validated against the request host / `cookieDomain`
- `GET /oauth/callback` — provider redirect target
- `GET /oauth/logout`
- `GET /api/auth/user` — session as JSON, or `{"authenticated":false}`

In handlers: `call.userSession`, `call.isAuthenticated` (from
`com.republicate.kroom.webapp.session`).

## Providers

Generic OIDC: set `oidcDiscoveryUri`, `oidcClientId`,
`oidcClientSecret`. Beyond the shortcuts, add `OidcProvider(...)`
instances to `providers`.

## Notes

- Session/flow cookies, `sessionSecret`, `externalUrl` and `cookieDomain`
  now live in `kroom-webapp-session` (`installSessions { }`).
- `redirect_uri` = `externalUrl + callbackUrl`; without `externalUrl`
  it is derived from the request (dev only — behind a proxy this
  requires `XForwardedHeaders`).
- To link OIDC logins into email+password accounts, pair with
  `kroom-webapp-auth`: `onAuthenticated = { s, _ -> authStore.linkOidc(s) }`.
