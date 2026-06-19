# Changelog

All notable changes to kroom will be documented in this file.

## [0.20] - 2026-06-19

### Added

#### kroom-webapp-velocity
- Read-only **scope chain** for every render: `application ⊂ session ⊂ request`,
  with the route model most specific. Register per-key providers in the install
  block — `application(key) { … }`, `session(key) { call -> … }`,
  `request(key) { call -> … }` — and the value is available in **every** template
  (e.g. `$user`) without each route passing it; the route model still wins on
  collision. Providers resolve lazily (memoized per render); scopes are
  read-through (`#set` lands in a fresh top context). Plugins self-register their
  own keys via `registerApplication/Session/Request`. `$versions` is now an
  application-scope provider. The low-level call-less `render(templatePath, model)`
  is unchanged.

#### kroom-webapp-l10n
- `$lang`, `$languages`, `$jsTranslations` are registered as request-scope values,
  so they are available on every render (not only the translated path), and
  `respondVelocityTranslated` now renders through the same scope chain — a
  translated page sees the full base context (`$user`, …) and still translates.

## [0.19] - 2026-06-18

### Changed

- bump ktor version to 3.5.0

## [0.18] - 2026-06-13

### Added

#### kroom-webapp-l10n
- `localeStrategy` (`URL_PREFIX` default, or `SESSION`): in `SESSION` mode the
  language lives in the session, not the URL — no `/{lang}/` is forced onto
  links (one canonical URL per page). A `/{lang}/` request pins the language in
  the session then 302s to the de-prefixed path (`/fr/x → /x`, `/fr → /`);
  every other path is served as-is, language resolved session → `Accept-Language`
  → `defaultLanguage`. `URL_PREFIX` behavior is unchanged.
- `SESSION` requires `installSessions` before `installL10n` (fails fast at
  startup otherwise); l10n gains an inert `implementation` dependency on
  kroom-webapp-session, unused under `URL_PREFIX`.

#### kroom-webapp-session
- `LocaleSession` plain `locale` cookie and `ApplicationCall.sessionLocale`
  getter/setter — anonymous-capable language pin, set before any `UserSession`
  exists.

## [0.17] - 2026-06-12

### Added

#### kroom-webapp-auth
- Email verification: with a configured `mailer`, `register` holds the
  registration pending behind an emailed 6-digit code (`{pending:true}`, no
  principal until confirmed) — `POST /api/auth/{verify,resend}`
- Password reset: `POST /api/auth/forgot` (always `{ok:true}`, no existence
  leak) and `POST /api/auth/reset` (sets the password and logs in; creates the
  credential if absent so OIDC-only accounts can gain one)
- Guest upgrade: `POST /api/auth/upgrade` attaches email+password to the
  authenticated no-email principal through the same code flow, preserving id
  and display name
- `Mailer` hook (app owns SMTP; awaited, failures answer 502 and keep the code
  for resend — cooldown and daily cap only advance on successful sends) with
  overridable `verifyEmail`/`resetEmail` bodies
- `AuthCodeStore` — pluggable pending-code storage, in-memory default with TTL;
  constant-time, attempt-limited code checks
- Abuse limits: per-IP rate limit on the auth routes (`rateLimitPerMinute`,
  429), per-address resend cooldown and daily mail cap
- Knobs: `requireVerification` (default true, effective with a mailer),
  `codeTtlSeconds`, `codeLength`, `maxVerifyAttempts`, `resendCooldownSeconds`,
  `maxMailsPerDay`, `rateLimitPerMinute`

### Changed

#### kroom-webapp-auth
- **`AuthStore` gained `setPassword(id, hash)` (upsert) and `setEmail(id,
  email)`** — consumers must implement both
- `Route.authRoutes` signature is now `(AuthConfig, Argon2Hasher, parser)` —
  use `installAuth { }`, which is unchanged

#### dependencies
- essential-kson 2.12 → 2.14 (2.13+ broke binary compatibility — align
  consumers), kddl 0.18 → 0.24

## [0.16] - 2026-06-11

### Added

#### kroom-webapp-oauth
- Apple, GitHub and LinkedIn providers, as `apple { teamId; keyId;
  servicesClientId; privateKey }`, `github { clientId; clientSecret }` and
  `linkedin { clientId; clientSecret }` config shortcuts
- `OAuth2Provider` — raw-OAuth2 path (no OIDC/id_token): explicit
  authorize/token/userinfo endpoints + `extractProfile(userInfo, fetch)`
  mapper; `githubProvider(...)` factory with the private-email fallback to
  `/user/emails` and overridable bases (GitHub Enterprise)
- `AppleClientSecret` — Sign in with Apple `client_secret` as an ES256 JWT
  signed with the `.p8` key (nimbus-jose), cached ~4 months
- `OidcProvider` knobs for non-vanilla providers: `scope`, `extraAuthParams`
  (Apple's `response_mode=form_post`), `clientSecretSupplier` (dynamic
  secrets), `clientSecretPost`, `requireNonce = false` (LinkedIn never echoes
  the nonce)
- `POST /oauth/callback` for `form_post` response mode; Apple's first-auth
  `user` field is parsed for the display name

### Changed

#### kroom-webapp-oauth
- `OAuthConfig.providers` is now a list of the new `OAuthProvider` interface
  (`OidcProvider` and `OAuth2Provider` implement it) — source-compatible for
  existing google/oidc consumers; `onAuthenticated` is unchanged and the
  session `id` remains the stable per-provider user id (OIDC `sub`, GitHub
  numeric id)

#### kroom-webapp-session
- The transient `auth_flow` cookie is `SameSite=None` when secure, so the
  login handshake survives cross-site `form_post` callbacks (Apple); stays
  `Lax` over plain http

## [0.15] - 2026-06-10

### Added

#### kroom-webapp-session (new module)
- Encrypted session identity shared by the authentication modules, extracted from
  kroom-webapp-oauth: `UserSession`, the `AuthFlow` redirect-login cookie, the
  single `installSessions { }` Sessions install, `validateReturnTo`, and
  `call.userSession` / `call.isAuthenticated`.

#### kroom-webapp-auth (new module)
- Email+password identity with OIDC account linking
- `AuthStore<ID>` — the app owns its dude/credentials schema and maps rows into
  `Principal<ID>` / `Credential` value projections; `AuthStoreException` rejects a
  write under app policy (e.g. an email-variant quota)
- argon2id hashing via BouncyCastle (pure-JVM), self-describing PHC strings,
  per-hash salt, optional pepper, cost read back on verify
- `normalizeEmail` (lowercase+trim; `+tag` variants kept distinct) and `emailBase`
  (`+tag` stripped, for app-side quota grouping)
- `installAuth<reified ID>` with a `String→ID` parser for Int/Long/String/Uuid (and
  an `idFromString` override), email-only `register`/`login`/`logout` routes, and
  `ApplicationCall.authId()`
- `linkOidc()` — wire into `installOAuth { onAuthenticated }` to find-or-create a
  principal by normalized email and attach the provider credential

### Changed

#### kroom-webapp-oauth (BREAKING)
- Session ownership moved to the new kroom-webapp-session module. Call
  `installSessions { sessionSecret; externalUrl; cookieDomain; cookieSecure }`
  **before** `installOAuth { }`; those four settings moved off `OAuthConfig`.
- `UserSession`, `validateReturnTo`, `ApplicationCall.userSession` /
  `isAuthenticated` moved from `com.republicate.kroom.webapp.oauth` to
  `com.republicate.kroom.webapp.session` — update imports.

## [0.14] - 2026-06-09

### Fixed

#### kroom-webapp-assets
- sse.js: pre-connect event handlers now bind on connect; one EventSource listener per event (was dropping `.onJson(...).connect()` handlers and double-binding later ones)

## [0.13] - 2026-06-09

### Changed

- IgnoreTrailingSlash installed by default

### Fixed

- webapp/$path should be static/$path
- l0n should ignore /oauth/, /events/

## [0.12] - 2026-06-05

### Added

#### kroom-webapp-oauth
- Working OIDC authorization-code flow (Google, generic OIDC, custom providers) — replaces the skeleton routes
- Encrypted + signed session cookies, keys derived from `sessionSecret` (previously plaintext, client-forgeable)
- `externalUrl`, `cookieDomain`, `cookieSecure` config for multi-subdomain deployments behind a reverse proxy
- `onAuthenticated` hook to enrich the session (e.g. `appId` for the app's own user id) or reject the login
- `returnTo` post-login redirect with open-redirect validation
- Cookies sent with `SameSite=Lax`

### Changed

#### kroom-webapp-oauth
- Dropped pac4j (no Ktor binding) in favor of direct `com.nimbusds:oauth2-oidc-sdk`

---

## [0.11] - 2026-02-24

### Fixed

#### kroom-view
- `ViewHandler.serve()` (Android): removed spurious `assets/` prefix that caused double-nesting (`assets/assets/...`). Now tries the path directly against Android assets, then falls back to `static/` for JAR resources.

---

## [0.10] - 2026-02-20

### Changed

#### kroom-server
- **Per-connection heartbeat** — replaced room-level keepalive with Ktor's per-session `heartbeat { period = 15.seconds }`, which writes directly to the socket and detects dead connections regardless of room activity
- Removed `keepAlive()`, `KEEPALIVE_DELAY`, `lastEventTime` from `Room`
- Simplified event loop to plain `eventQueue.receive()`

---

## [0.9] - 2026-02-07

### Added

#### kroom-webapp-l10n
- `#translate` Velocity directive — like `#parse`, but applies translation to included templates
  - Extends `Parse`, overrides `getTemplate()` to run through active `Translator`
  - Requires `Translator.current` ThreadLocal to be set

#### kroom-webapp-velocity
- Auto-registers `TranslateDirective` when l10n module is on the classpath

---

## [0.8] - 2026-02-06

### Added

#### kroom-server
- **User-centric architecture** for multi-tab support
  - `User` class: persistent identity with connections-per-room tracking
  - `Users` global registry: single `User` instance per userId across the application
  - `Actor` now references `User` instead of bare `userId`
  - `sendToSeat()` reaches all user connections, not just one
  - `onActorLeft(actor, userFullyLeft)` — `userFullyLeft=true` when user's last connection closes
- **Player status tracking** (online/idle/away/offline)
  - `PlayerStatus` enum with ONLINE, IDLE, AWAY, OFFLINE states
  - `Seat.status` and `Seat.statusChangedAt` fields
  - `updatePlayerStatus()` / `updatePlayerStatusByConnection()` methods
  - `handleStatusAction()` for client-initiated status changes
  - `player_status` SSE event broadcast
  - Automatic ONLINE/OFFLINE broadcast on reconnect/disconnect
- **Full history replay on fresh connect** — new connections without Last-Event-ID now receive the entire history buffer

#### kroom-webapp-assets
- `status.js` — client-side idle/away detection via Page Visibility API
  - `StatusTracker` class with configurable idle timeout
  - `PlayerStatus` constants (ONLINE, IDLE, AWAY, OFFLINE)
- `api.js`: configurable API base URL via `window.kroomApiBase` (for native apps with bundled assets)

#### kroom-view
- `KroomWebView` (iOS): injects `window.kroomApiBase` via `WKUserScript` for native apps

### Changed

#### kroom-server (BREAKING)
- `Actor` constructor takes `User` instead of `userId` string
- `Table.Seat` references `User` instead of `connectionId`
- `Seat.isConnected` now takes `roomId` parameter
- `onActorLeft(actor, userFullyLeft)` replaces `onActorLeft(actor)`
- Deprecated shims provided for old API signatures

### Fixed

#### kroom-server
- `handleStatusAction`: use `userId` to find seat, not ephemeral `connectionId`

---

## [0.7] - 2026-02-04

### Added

#### kroom-server
- **Last-Event-ID replay support** for SSE reconnection
  - `Room.needsHistory()` - override to enable event buffering (default: false)
  - `Room.historyBufferSize` - configurable buffer size (default: 50)
  - `Room.historicizableEvents` - mutable set of event names to buffer (configurable at runtime)
  - `Room.join(actor, lastEventId)` - accepts optional Last-Event-ID header
  - Server restart detection: stale client IDs are ignored
  - Selective replay: only events in `historicizableEvents` are buffered (e.g., "chat" but not "rolled")
- `ChatRoom` now enables history replay for chat messages

#### kroom-webapp-assets
- `Element.isVisible()` / `NodeList.isVisible()` - checks if element is visible (not hidden, not display:none)

---

## [0.6] - 2026-01-26

### Added

#### kroom-webapp-velocity
- `devDir` configuration for template hot reload in dev mode
- Dev mode uses both file and classpath loaders for macro library support

### Fixed

#### kroom-webapp-l10n
- Fall back to English for empty translations

---

## [0.5] - 2026-01-25

### Added

#### kroom-webapp-core
- **Configurable static routes** via `installCore { static { ... } }`
  - `prefixes`: list of path prefixes to serve (default: `css`, `js`, `img`, `fonts`, `lib`, `snd`)
  - `devMode`: when true, serves from filesystem first with classpath fallback
  - `devDir`: filesystem directory for dev mode hot-reload
- Added `fonts` to default static prefixes (was missing, causing 404 for font files)

### Changed
- `staticRoutes()` now accepts `StaticConfig` parameter (backward compatible, defaults work)
- Dev/prod static serving unified: single configuration point instead of duplicate route definitions
- Removed unused kotlinx-serialization dependency

### Fixed

#### kroom-webapp-l10n
- Fixed missing translation auto-insert bug

---

## [0.4] - 2026-01-24

### Changed
- Ktor upgraded to 3.4.0

---

## [0.3] - 2026-01-24

### Added

#### kroom-webapp-push (new module)
- Web Push notifications support via `nl.martijndwars:web-push`

#### kroom-webapp-assets
- `domhelper.js`: `toggleClass(className, force)` now supports force parameter
- `api.js`: Error objects now include `status` and `data` properties
- `sse.js` - SSE client with platform abstraction
  - Browser uses native `EventSource`
  - Native WebView can inject `window.kroomSSE` to delegate to app
  - Auto-reconnect with configurable delay
  - `onJson(eventName, handler)` for automatic JSON parsing
- `store.js` enhancements:
  - `createSelector(...inputSelectors, resultFn)` - memoized selectors
  - `subscribeToSlice(store, selector, callback)` - selective subscriptions
  - `createAction(type, payloadCreator)` - action creator factory

#### kroom-webapp-core
- `kroomServer()` helper with HTTP/2 cleartext (h2c) support
  - `KroomServerConfig` with `h2c = true` by default
  - Solves browser's 6-connection limit for SSE via multiplexing

#### kroom-view
- iOS WKWebView support
  - `ViewHandler` for serving bundle resources
  - `KroomURLSchemeHandler` for intercepting kroom:// scheme
  - `createKroomWebView(config)` factory function
- Android `KroomActivity` enhancements
  - `onNetworkError()` / `onHttpError()` callbacks for custom error pages
  - Configurable static prefixes instead of passthrough logic

#### kroom-server
- `Actor` identity model: `connectionId` (ephemeral) + `userId` (persistent) + `name`
  - `connectionId` = unique per SSE connection (changes on reconnect)
  - `userId` = persistent identity for seat matching (e.g. `dudeId#token`)
  - `isAuthenticated` property (true when `userId` is set)
- `Table.Seat` tracks `userId`, `playerName`, `connectionId` separately
  - Reconnection matches by `userId` instead of name
  - `assignSeat(connectionId, userId, playerName, requestedSeat?)` new signature
- `Table.stateToJsonForSeat()` now includes `spectators` list by default

### Changed
- Kotlin upgraded to 2.3.0
- Ktor upgraded to 3.3.0 (h2c support)
- essential-kson upgraded to 2.12
- kddl upgraded to 0.18
- Removed `slf4j-simple` from library modules (applications should provide their own SLF4J implementation)
- **kroom-webapp-assets**: JS assets moved from `webapp/js/` to `static/js/` (now served by `staticRoutes()`)
- `Actor.id` deprecated, use `connectionId` instead

### Fixed

#### kroom-server
- `Table.sendStateTo()` now looks up seat by `userId` first, fixing `mySeat` for multi-tab and reconnect scenarios
- `Room.join()` now calls `onActorJoined()` before `sendStateTo()`, ensuring seats are assigned before initial state is sent

#### kroom-webapp-l10n
- Preserve query string during language redirect

---

## [0.2] - 2025-12-08

### Added

#### kroom-server
- `Table<S>` class for seat-based games
  - Seat assignment with reconnect by name
  - `mySeat` included in state payload
  - `assignSeat(actorId, playerName, requestedSeat?)` for explicit seat selection
- `Room.sendStateTo()` now open for override

#### kroom-webapp-assets (new module)
- `domhelper.js` - lightweight jQuery-like DOM manipulation
- `api.js` - fetch wrapper for REST APIs with error handling
- `store.js` - minimal Redux-like state management
  - `createStore(reducer, initialState, enhancer?)`
  - `applyMiddleware(...middlewares)`
  - `combineReducers(reducers)`
  - Built-in `logMiddleware` and `thunkMiddleware`
- `kroomAssets()` Ktor route for serving assets
- `KroomAssets.coreScripts()` helper for script tags with versioning

### Changed
- Documentation expanded with module overview and API references

---

## [0.1] - 2024-12-05

Initial release.

### Added

#### kroom-server
- `Room<S>` base class for SSE-based real-time rooms
- `Lobby` singleton for room lifecycle (register/get/remove)
- `Actor` representing connected clients
- `Spectator` for non-participating viewers
- `ActionResult` sealed class (Success/Error) for action responses
- Keep-alive mechanism (configurable interval)

#### kroom-webapp-core
- Ktor webapp foundation
- `staticRoutes()` for serving static assets from classpath
- `respondJson {}` DSL for JSON responses

#### kroom-webapp-velocity
- Velocity template engine integration for Ktor

#### kroom-webapp-l10n
- Gettext-based i18n
- `L10nPlugin` for Ktor

#### kroom-webapp-oauth
- OAuth2 authentication plugin

#### kroom-common
- Multiplatform core types

#### kroom-view
- Client-side SSE handling (JS/Wasm targets)

#### kroom-examples
- `chifoumi` - Rock-paper-scissors game demonstrating lobby/matchmaking
