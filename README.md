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
kroom-webapp-oauth    OAuth2 authentication
```

## Features

- Room-based real-time sessions with SSE
- Actor presence and reconnection handling
- Last-Event-ID replay for selective event types (e.g., chat)
- Table abstraction for seat-based games
- Event broadcasting and keep-alive
- Coroutine-based async processing

## Quick Start

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.republicate.kroom:kroom-server:0.7")
    implementation("com.republicate.kroom:kroom-webapp-assets:0.7")
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

## Table (for seat-based games)

```kotlin
class GameRoom(id: String) : Table<GameState>(id, seatCount = 2) {
    override var state = GameState()

    override fun handleAction(actor: Actor, action: Json.Object): ActionResult {
        when (action.getString("type")) {
            "join" -> {
                val seat = assignSeat(actor.id, actor.name, requestedSeat = null)
                // seat is 1-indexed, null if table full
            }
        }
    }
}
```

The `Table` class:
- Tracks seats (player name + actor ID separately for reconnect)
- Sends `mySeat` in state payload so clients know their position
- Handles reconnection by name matching

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
