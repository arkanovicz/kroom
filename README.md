# Kroom

Kotlin multiplatform library for real-time collaborative applications using Server-Sent Events (SSE).

## Modules

```
kroom-common          multiplatform core types
kroom-view            client-side SSE handling (JS/Wasm)
kroom-server          server-side Room/Lobby/Actor abstractions
kroom-webapp-core     Ktor webapp foundation (routing, API helpers)
kroom-webapp-assets   shared client-side JS/CSS
kroom-webapp-velocity Velocity template integration
kroom-webapp-l10n     i18n with gettext
kroom-webapp-session  encrypted session identity (shared by oauth/auth)
kroom-webapp-oauth    OIDC authentication
kroom-webapp-auth     email+password identity with OIDC linking
kroom-webapp-push     Web Push notifications
kroom-markdown        %-Velocity markdown blocks, #markdown directive (ktor-free)
kroom-webapp-authoring in-place block editing (locks, content stores, edit API, editor)
```

## Features

- Room-based real-time sessions with SSE
- Actor presence and reconnection handling
- Last-Event-ID replay for selective event types (e.g., chat)
- Table abstraction for seat-based games with player status tracking
- Multi-tab support via User-centric identity model
- Per-connection heartbeat via Ktor SSE (dead connection detection)
- Coroutine-based async processing

## Quick Start

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.republicate.kroom:kroom-server:0.21")
    implementation("com.republicate.kroom:kroom-webapp-assets:0.21")
}
```

```kotlin
// Define a room
class ChatRoom(id: String) : Room<ChatState>(id) {
    override var state = ChatState()
    override fun stateToJson() = state.toJson()
    override fun handleAction(actor: Actor, action: Json.Object): ActionResult {
        // handle chat messages
    }
}

// Setup Ktor
routing {
    kroomAssets()  // serve shared JS/CSS
    sseRoute("/events/{room}") { roomId, actor -> Lobby.join(roomId, actor) }
    post("/api/{room}/action") { /* dispatch to room */ }
}
```

## Heartbeat and Dead Connection Detection

SSE connections can die silently — browser killed, network dropped without TCP FIN. Without heartbeat, the server blocks forever on `channel.receive()`, never discovering the failure.

Ktor's SSE heartbeat solves this: it periodically writes a comment line to the socket. When the write fails on a dead connection, the exception propagates and terminates the SSE handler, triggering cleanup.

**This must be configured on each SSE route**, not on the Room:

```kotlin
sse("/events/{room}") {
    heartbeat { period = 15.seconds }

    val actor = Actor(connectionId = "...", name = login)
    val channel = room.join(actor)

    try {
        for (event in channel) {
            send(event)
        }
    } finally {
        room.leave(actor)
    }
}
```

The `heartbeat` block is Ktor's built-in per-connection heartbeat (since Ktor 3.1). It sends SSE comment lines at the configured interval regardless of room activity. A shorter period detects dead connections faster but generates more traffic.

| Period | Detection | Notes |
|--------|-----------|-------|
| 2-5s | Fast | Good for interactive apps (games, chat) |
| 15s | Moderate | Default in kroom examples |
| 30s | Ktor default | Sufficient for low-frequency updates |

**Without heartbeat, dead connections are never detected** unless the room happens to send an event that fails. Always configure it.

## kroom-webapp-core

Ktor webapp foundation with `installCore()` plugin:

```kotlin
installCore {
    logLevel = Level.INFO  // CallLogging level

    static {
        // Default prefixes: css, js, img, fonts, lib, snd
        prefixes = listOf("css", "js", "img", "fonts", "lib", "snd")

        // Dev mode: serve from filesystem first, fallback to classpath
        devMode = true
        devDir = File("src/main/resources/static")
    }
}
```

### Static Routes

Default prefixes (configurable via `prefixes`):

| Route | Source |
|-------|--------|
| `/css/*` | `static/css/` |
| `/js/*` | `static/js/` |
| `/img/*` | `static/img/` |
| `/fonts/*` | `static/fonts/` |
| `/lib/*` | `static/lib/` |
| `/snd/*` | `static/snd/` |

Assets served from classpath (production) or filesystem with classpath fallback (dev mode).

## kroom-webapp-assets

Shared client-side libraries for Ktor webapps:

| File | Description |
|------|-------------|
| `domhelper.js` | Lightweight jQuery-like DOM manipulation |
| `api.js` | Fetch wrapper for REST APIs |
| `store.js` | Minimal Redux-like state management |

### Usage

```kotlin
routing {
    kroomAssets()           // serves at /js/kroom/, /css/kroom/
    kroomAssets("/static")  // serves at /static/js/kroom/, etc.
}
```

```html
<script src="/js/kroom/domhelper.js"></script>
<script src="/js/kroom/api.js"></script>
<script src="/js/kroom/store.js"></script>
```

Or with cache-busting:
```kotlin
// In template context
KroomAssets.coreScripts()  // returns script tags with version param
```

### domhelper.js

```javascript
// Selectors
$('#id')              // single element or NodeList
$$('.class')          // always NodeList

// Chaining
$('#btn').addClass('active').on('click', fn)

// Events
$('.items').on('click', e => { ... })

// Classes
el.addClass('foo bar').removeClass('baz').toggleClass('active')

// Attributes & properties
el.attr('href')       // get
el.attr('href', url)  // set
el.prop('checked', true)
el.data('id')         // data-id attribute

// Content
el.text('hello')
el.html('<b>hi</b>')
el.val()              // form value
el.empty()
el.load('/api/frag')  // fetch and inject HTML

// Visibility
el.show().hide()

// Forms
form.field('email')              // get value
form.field('email', 'a@b.com')   // set value

// Misc
el.find('.child')
el.index()
el.busy(true)         // toggle .busy class
dialog.showModal()
```

### api.js

```javascript
// Low-level (returns Response)
api.get('users')
api.post('users', { name: 'Jo' })
api.put('users/1', { name: 'Jo' })
api.delete('users/1')

// Helpers (returns parsed data, throws on error)
api.getJson('users')           // GET -> JSON
api.getHtml('fragment')        // GET -> HTML string
api.postJson('users', data)    // POST -> JSON
api.putJson('users/1', data)   // PUT -> JSON
api.deleteJson('users/1')      // DELETE -> JSON
```

### store.js

```javascript
// Create store
const store = createStore(reducer, initialState);
// or with middleware
const store = createStore(reducer, initialState, applyMiddleware(logMiddleware));

// Use
store.getState()
store.dispatch({ type: 'INCREMENT' })
store.subscribe(() => render(store.getState()))

// Combine reducers
const reducer = combineReducers({ todos: todosReducer, ui: uiReducer });

// Built-in middleware
logMiddleware    // console.log actions and state
thunkMiddleware  // dispatch functions for async
```

## kroom-markdown

Editable content blocks: markdown files with `%` directives (`%if`, `%foreach`, `$refs`, `%%@` headers),
rendered by a `#`-template layout through `#markdown($view.path)`.

```properties
markdown.resource.loaders = file
markdown.resource.loader.file.path = /data/content
```

Blocks are watched by default — strict mode, sandbox (`SecureUberspector`), `introspector.restrict.writes = *`;
relax any of it under `markdown.*`.

Rendering is JVM-only on purpose: flexmark has no multiplatform build, server rendering is ktor/JVM
anyway, and kroom's multiplatform scope is model sharing, not rendering.

## kroom-webapp-authoring

In-place editing of those blocks: the block a visitor reads is the block an author edits, through the same
tree — a `ResourceStore` is a velocity `ResourceLoader` first, so a submit writes the very bytes the next
render reads.

```kotlin
installSessions { … }
installVelocity {
    // authoring cannot choose this: velocity's engine is built at install, before authoring exists
    properties["markdown.block.wrapper"] = "kroom/block-wrapper.html"
}
installAuthoring {
    store = FileResourceStore(Path.of("/data/content"))  // or MemoryResourceStore(), or your own
    lockTimeout = 2.minutes                           // untouched that long, a block is free again
    apiPrefix = "/api/content"                        // must live under /api/ — api.js roots calls there
    canEdit = { session, path -> session?.id in editors }
    placeholder = "*(nothing here yet)*"
}
```

### The edit API

```
GET    {prefix}/{path...}[?rev=]   the block, its lock, whether the caller may edit it
POST   {prefix}/lock/{path...}     take the block — 409 names who holds it, re-entrant for its owner
DELETE {prefix}/lock/{path...}     give it back, unwritten
POST   {prefix}/{path...}          submit {rev, body} — 409 answers {message, theirs} on a stale rev
GET    {prefix}/history/{path...}  revisions of one block   ] 404 unless the store is Versioned
GET    {prefix}/journal            the site-wide log        ]
```

401 without a session, 403 when `canEdit` says no. The lock routes read `/lock/…` rather than `…/lock`
because a ktor tailcard takes every remaining segment. A submit carries the rev it started from, so an edit
made meanwhile — a concurrent author, a `git pull` — is answered with *theirs* instead of being overwritten:
the lock is the polite path, the rev check is the safe one.

**History and the journal exist only when the store is `Versioned`** — a plain `FileResourceStore` keeps no
past, and both routes answer 404. There is no `revert` route either: restoring an old body writes it as a
new revision through the ordinary submit, which keeps the journal honest.

### The page side

`markdown.block.wrapper` names a template the `#markdown` directive renders in place of the bare html, with
`$path`, `$name` and `$html` added to the page context. This module ships the default one at the classpath
root as `kroom/block-wrapper.html` — root, so it resolves under every engine shape (dev's `classpath` loader,
production's `root` loader) — and it emits the block plus, for an author `$authoring.canEdit` accepts, two
buttons. The editor markup itself is built by `authoring.js` when editing starts: a visitor downloads none
of it.

The layout carries three files, after the house stack (domhelper.js, api.js), which `authoring.js` builds on:

```html
<link rel="stylesheet" href="/css/authoring.css?v=…">
<script src="/lib/diff-match-patch/diff_match_patch.js?v=…"></script>
<script src="/js/authoring.js?v=…"></script>
```

or, from a velocity layout, `$authoring.assets.tags()`. They are served by `installCore`'s static routes,
which read `static/` from any jar on the classpath — authoring mounts no route for them.

### What the editor does

✎ takes the lock and swaps in a textarea holding the body; typing refreshes the lock (debounced, 700ms);
✓ submits `{rev, body}`, ✗ gives the block back. ⟲ lists the revisions in a `<dialog>`; picking one diffs it
against the block as it stands, and *restore* loads that body into the editor for you to submit — an undo is
an edit like any other. A 409 on submit shows yours beside theirs, word-diffed, and you leave it editing
against their revision, keeping your text or taking theirs.

There is no preview, because there is no route that renders a body nobody has submitted: markdown is
rendered on the server, inside a page. So editing keeps the *published* rendering — the very nodes the page
rendered — in a fold beside the textarea, marked "before your changes" as soon as you type. It answers what
you are changing, never what it will become. For the same reason a successful submit reloads the page: the
submit answers a rev, and the page is the only thing that knows how to render the block.

## Table (for seat-based games)

```kotlin
class GameRoom(id: String) : Table<GameState>(id, seatCount = 2) {
    override var state = GameState()

    override fun handleAction(actor: Actor, action: Json.Object): ActionResult {
        when (action.getString("type")) {
            "join" -> {
                val seat = assignSeat(actor.user!!, actor.name, requestedSeat = null)
                // seat is 1-indexed, null if table full
            }
        }
    }
}
```

The `Table` class:
- Tracks seats via `User` identity (not connection ID) for multi-tab support
- Sends `mySeat` in state payload so clients know their position
- Handles reconnection by user identity matching
- Player status tracking (online/idle/away/offline) with `Seat.status`

## Last-Event-ID Replay

SSE supports automatic reconnection with `Last-Event-ID` header. kroom can replay missed events selectively:

```kotlin
class MyChatRoom(id: String) : Room<MyState>(id) {
    init {
        historicizableEvents.add("chat")  // Only "chat" events are replayed
    }

    override fun needsHistory() = true  // Enable event buffering
}
```

- Only events in `historicizableEvents` are buffered and replayed
- Game events (rolled, played, etc.) use state-on-join, not replay
- Server restart detection: stale client IDs are ignored
- Buffer size configurable via `historyBufferSize` (default: 50)

Ideal for games with chat: game state is authoritative, chat history is replayed on reconnect.

## Run Examples

```bash
./gradlew :kroom-server:run           # SSE playground at :8080/playground
./gradlew :kroom-examples:chifoumi:run  # Rock-paper-scissors at :8081
```

## License

Apache 2.0
