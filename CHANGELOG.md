# Changelog

All notable changes to kroom will be documented in this file.

## [Unreleased] - 0.2

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
