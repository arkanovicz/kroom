# kroom-webapp-oauth

OIDC authentication for Ktor webapps — authorization-code flow with
encrypted cookie sessions.

## Usage

```kotlin
fun Application.module() {
    installOAuth {
        sessionSecret = System.getenv("SESSION_SECRET")
        googleClientId = System.getenv("GOOGLE_CLIENT_ID")
        googleClientSecret = System.getenv("GOOGLE_CLIENT_SECRET")

        // single app serving many subdomains behind a reverse proxy
        externalUrl = "https://example.com"
        cookieDomain = ".example.com"

        // enrich or reject logins; returning null rejects
        onAuthenticated = { session, call ->
            session.copy(appId = findOrCreateUser(session))
        }
    }
}
```

## Routes

- `GET /oauth/login/{provider}?returnTo=...` — start login (`google`,
  `oidc`, or a custom provider name); `returnTo` defaults to the
  `Referer` and is validated against the request host / `cookieDomain`
- `GET /oauth/callback` — provider redirect target
- `GET /oauth/logout`
- `GET /api/auth/user` — session as JSON, or `{"authenticated":false}`

In handlers: `call.userSession`, `call.isAuthenticated`.

## Providers

Generic OIDC: set `oidcDiscoveryUri`, `oidcClientId`,
`oidcClientSecret`. Beyond the shortcuts, add `OidcProvider(...)`
instances to `providers`.

## Notes

- Session and flow cookies are encrypted and signed with keys derived
  from `sessionSecret`.
- `redirect_uri` = `externalUrl + callbackUrl`; without `externalUrl`
  it is derived from the request (dev only — behind a proxy this
  requires `XForwardedHeaders`).
