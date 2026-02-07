# Changelog

All notable changes to kroom will be documented in this file.

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
