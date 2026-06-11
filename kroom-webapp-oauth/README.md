# kroom-webapp-oauth

OIDC and raw-OAuth2 authentication for Ktor webapps — authorization-code
flow over the encrypted sessions from `kroom-webapp-session`.

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

        github {
            clientId = System.getenv("GITHUB_CLIENT_ID")
            clientSecret = System.getenv("GITHUB_CLIENT_SECRET")
        }

        linkedin {
            clientId = System.getenv("LINKEDIN_CLIENT_ID")
            clientSecret = System.getenv("LINKEDIN_CLIENT_SECRET")
        }

        apple {
            teamId = System.getenv("APPLE_TEAM_ID")
            keyId = System.getenv("APPLE_KEY_ID")
            servicesClientId = System.getenv("APPLE_SERVICES_CLIENT_ID")
            privateKey = System.getenv("APPLE_PRIVATE_KEY") // .p8 PEM (or bare base64)
        }

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
  `apple`, `github`, `linkedin`, `oidc`, or a custom provider name);
  `returnTo` defaults to the `Referer` and is validated against the
  request host / `cookieDomain`
- `GET|POST /oauth/callback` — provider redirect target (POST for
  Apple's `form_post`)
- `GET /oauth/logout`
- `GET /api/auth/user` — session as JSON, or `{"authenticated":false}`

In handlers: `call.userSession`, `call.isAuthenticated` (from
`com.republicate.kroom.webapp.session`).

## Providers

Shortcuts: `googleClientId/Secret`, `apple { }`, `github { }`,
`linkedin { }`, and generic OIDC via `oidcDiscoveryUri`,
`oidcClientId`, `oidcClientSecret`.

Beyond the shortcuts, add instances to `providers`:

- `OidcProvider(...)` — any OIDC provider, by discovery URI or injected
  metadata. Knobs for the quirky ones: `scope`, `extraAuthParams`,
  `clientSecretSupplier` (dynamic secrets), `clientSecretPost`
  (secret in the POST body instead of Basic auth), `requireNonce = false`
  for providers that never echo the nonce.
- `OAuth2Provider(...)` — raw OAuth2 without OIDC (no id_token):
  explicit authorize/token/userinfo endpoints, scope, and an
  `extractProfile(userInfo, fetch)` mapper; `fetch` does authorized
  GETs for providers needing extra calls. `githubProvider(clientId,
  clientSecret, webBase, apiBase)` is the canonical example
  (overridable bases for GitHub Enterprise).

### Provider notes

- **Apple** — `client_secret` is an ES256 JWT generated from the `.p8`
  key (cached ~4 months); callback arrives as a cross-site `form_post`
  (see the `SameSite` note in `kroom-webapp-session`); email comes from
  the validated id_token, the name only from the first authorization;
  requires https.
- **GitHub** — not OIDC; when the email is private, falls back to
  `/user/emails` (primary-verified, then any verified). Identity is the
  numeric GitHub id.
- **LinkedIn** — standard OIDC, but the nonce is never echoed in the
  id_token, so nonce validation is off (state still covers CSRF).

## Account linking

`onAuthenticated` receives a `UserSession` carrying everything needed
to link accounts app-side: `provider`, `email`, and `id` — the
**stable per-provider user id** (OIDC `sub`, GitHub numeric id). Store
the `(provider, id)` credential and match by normalized email; kroom
owns the flow, the app owns the store. With `kroom-webapp-auth`:
`onAuthenticated = { s, _ -> authStore.linkOidc(s) }`.

Apple only sends the email on the first authorization — link on first
login and rely on `id` afterwards.

## Notes

- Session/flow cookies, `sessionSecret`, `externalUrl` and `cookieDomain`
  live in `kroom-webapp-session` (`installSessions { }`).
- `redirect_uri` = `externalUrl + callbackUrl`; without `externalUrl`
  it is derived from the request (dev only — behind a proxy this
  requires `XForwardedHeaders`).
