# Kroom

Kotlin multiplatform library for real-time collaborative applications using Server-Sent Events (SSE).

## Modules

```
kroom-common          the common declaration of a room: State, events, RoomEvents, Logic (KMP)
kroom-client          a page on a room: the stream folded into the State, its regions merged into the DOM by the engine's sink after every frame (KMP jvm+js)
kroom-client-text     a text written by several, its region: kroom/text.html, compiled under the prefix kroom (KMP js)
kroom-view            client-side SSE handling (JS/Wasm)
kroom-server          rooms on the JVM: the loop, presence, roles, Route.room / rooms (stream, intents, catalog), lobbies
kroom-webapp-core     Ktor webapp foundation (routing, API helpers)
kroom-webapp-assets   shared client-side JS/CSS
kroom-webapp-velocity Velocity template integration
kroom-webapp-l10n     i18n with gettext
kroom-webapp-session  encrypted session identity (shared by oauth/auth)
kroom-webapp-oauth    OIDC authentication
kroom-webapp-auth     email+password identity with OIDC linking
kroom-webapp-push     Web Push notifications
kroom-webapp-images   resized images through imgproxy, cached on disk, local and remote sources
kroom-markdown-core   markdown to sanitized html: flexmark + jsoup, no velocity
kroom-markdown        %-Velocity markdown blocks, #markdown directive (ktor-free)
kroom-webapp-authoring in-place block editing, storage/identity APIs, plugins, admin bar
kroom-plugin-scratchpad  a text several people write at once, in a page: `$scratchpad.show()` (demo only, unpublished)
kroom-webapp-authoring-git  the content store as a git repository: every write a commit, history and journal (kroom-webapp-authoring/git/)
kroom-plugin-*        example plugins (kroom-webapp-authoring/plugins/): webmaster, forms, gallery
kroom-webapp-authoring-demo  the authoring stack as a thing you can click (kroom-webapp-authoring/demo/)
```

kroom-webapp-l10n: a library ships its catalog as po files at a path of its own in its jar (never the application's
`/i18n`), loaded with `application.addCatalog("/<lib>/i18n")` after the application's `installL10n`; the
application's entries win.
`call.language = "fr"` pins a request's language (a configured one), whatever the session or the URL says.
A page hands its scripts the catalog in one line, `<script>window.i18n = $jsTranslations;</script>` (an object:
velocity writes a map as a literal there), before the scripts that call `t()` (`i18n.js`, kroom-webapp-assets).

## Features

- Rooms over events: HTTP intents up, the verdict as the reply, SSE frames down
- One declaration in common Kotlin (State, events, logic) for the server and the browser
- Per-viewer visibility (selective and hidden fields), presence as a State field (a room's, or a group's in a lobby)
- Server-rendered chrome, room regions merged into the DOM by the engine's sink
- Multi-tab support via a user-centric identity model
- Per-connection heartbeat (dead connection detection)
- Table abstraction for seat-based games (the older `on()` path)

## Quick Start

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.republicate.kroom:kroom-server:0.21")
    implementation("com.republicate.kroom:kroom-webapp-assets:0.21")
}
```

```kotlin
// common code: the State, the events, the alphabet, the logic (see Events below)
val chatRoom = ChatRoom("lobby")   // a chat room: ChatState, ChatEvents, ChatLogic

// Setup Ktor
routing {
    kroomAssets()  // serve shared JS/CSS
    route("/api") {
        room(chatRoom) { call -> call.sessions.get<MySession>()?.let { Users.getOrCreate(it.id, it.name) } }
    }
}
```

## Events

A room is a **State**, an **alphabet of events**, a **logic**, one loop and one stream. The browser
posts an intent over HTTP and gets the verdict as the reply; every fact comes down the room's SSE
stream as a frame. The State, the events and the logic are common Kotlin: the same classes run on
the server and, compiled to JS, in the browser.

```
browser  click [data-kroom-intent=play] ──POST /api/rooms/<room>/play {move}──▶ door: shape, permission (stateless)
         trigger freed / reason shown  ◀─── 200 {} | 400 {reason, args} ────┤ loop: check, then the logic decides
         fold + merge into the DOM     ◀──────── SSE: the frames ───────────┘ the intent's frame, then what it emits

event: play   id: 42   data: {"player":"a"}
```

### Declaring a room (common code)

Four declarations, the matchmaking part of the chifoumi example:

```kotlin
// the data: a view over the room's JSON, reduce its only writer
class ChifoumiState(json: Json.MutableObject = Json.MutableObject()) : ChatState<Chat>(json, line = ::Chat) {
    val queue by keyed(::Player)                     // who waits, by id, in order
    override fun reduce(event: Event) {
        super.reduce(event)                           // the chat's, then presence (users)
        when (event) {
            is Match -> event.player?.let { queue.add(it) }
            is Cancel -> if (event.intent == "match") event.user?.let { queue.remove(it) }
            is Leave -> event.user?.let { queue.remove(it.id) }
            else -> {}
        }
    }
}

// an event: one name, one class, both directions; fields are declared, so the class is also data
class Match(json: Json.MutableObject = Json.MutableObject()) : Event("match", json = json) {
    var player by obj(::Player).out                   // the logic fills it; a client sending it is refused
    init { check { state: ChifoumiState, actor -> if (actor.id in state.queue) Reason("in_queue") else null } }
}

// the alphabet: every name on the wire; join, leave, status, cancel are kroom's, in every room
object ChifoumiEvents : ChatEvents<Chat>(::Chat) {
    val match = intent(::Match, cancellable = true)   // a client may post it, and withdraw its deposit
    val round = event(::Round)                        // only the logic emits it
}

// the decisions: one typed handler per intent, pure; the platform's pieces come in by the constructor
class MatchmakingLogic(private val ids: () -> String) : Logic<ChifoumiState>(ChifoumiEvents) {
    init {
        on(ChifoumiEvents.match) { state, actor, match ->
            match.player = Player(Json.MutableObject("id" to actor.id, "name" to actor.name))
            done                                       // the intent's own frame: the fold queues the player
        }
        cancel(ChifoumiEvents.match) { state, actor, _ -> if (actor.id in state.queue) done else Reason("nothing_to_cancel") }
    }
}
```

- **State.** Fields are delegates over the JSON (declare them `private set`): the logic reads,
  `reduce` writes, on the server for its own frames and in the browser for the same frames, so
  `reduce*(snapshot@N, frames]N,M]) == snapshot@M` for every viewer. Nothing lives beside the State:
  a secret is a field of it, visible to nobody or to some (below).
- **Events.** A field is `in` (a client sends it, validated) or `.out` (the logic fills it);
  `.required` refuses its absence. `check { state: S, actor -> Reason? }` is the stateful half of
  validation, typed on the State it reads: the browser runs it before posting, the room first on its
  loop, and a room refuses at construction an event checked on another State.
- **RoomEvents** is the alphabet as an object of typed entries: `intent(::E, cancellable)` (postable)
  or `event(::E)` (the room's: a client never posts it, server code may act it — a store's answer). It feeds the
  door, the routes, the browser's fold, posts and stream, and the catalog (`describe()`, served as `GET …/events`). Composition is inheritance:
  `ChatEvents` brings `chat`.
- **Logic.** `on(entry) { state, actor, event -> Outcome }` per intent, typed on the entry's class;
  a `Reason` is the reply (nothing framed), an `Emit` (`emit(…)`, `done`) frames the intent then each
  event it emits, each folded as it goes, each a name of the room's events. kroom's `join`, `leave`
  and `status` reach a handler when the logic declares one. A missing handler, or a cancellable intent
  without its `cancel`, is refused when the room is built.
- **Cancellable.** A cancellable intent leaves a deposit in the State through `reduce` (the queue
  above); `cancel {intent}` reaches the logic's `cancel(entry)`, which reads the State for what there
  is to withdraw. The room holds no token; the viewer knows what it deposited from the State.

**Field kinds**: `string()`, `int()`, `long()`, `double()`, `bool()`, `enum<E>()` (read whatever the
case), `obj()` (plain JSON), `array()`, `obj(::V)` (a typed view), `keyed(::V)` (views by `id`, in
insertion order, an object on the wire: what a page lists whose rows change in place), `log(::V, max)`
(append-only, capped), `seq(::V)` (views by `id` in an order that is itself state, an array on the wire,
written by `splice(index, count, items)` alone: what people insert into, cut from and rearrange). A setter
given null removes the key.

**Visibility** is a field's: public, or selective with `.visibleTo { viewer -> … }`, a rule over the
viewer's user id read on the instance (`var move1 by enum<Move>().visibleTo { it == player1 }`);
`.hidden` is `visibleTo { false }`, the server's alone (a deck not dealt yet). A frame carrying a
selective field is serialized once per user, any other once for all; a snapshot is the viewer's view.
A State field fed by a selective event field carries the same rule, so each viewer's fold is its
snapshot. `Event.to(users…)` addresses a frame to some users: a message, not folded.

### Serving it (kroom-server)

```kotlin
object Arena : ChatRoom<Chat, ChifoumiState>("lobby", MemoryChatHistory(), ChifoumiState(), MatchmakingLogic(ids))

routing {
    route("/api") {
        room(Arena) { call -> call.sessions.get<MySession>()?.let { Users.getOrCreate(it.id, it.name) } }
    }
}
```

`room(room) { call -> user }` reads the room's events and mounts everything it serves: the stream
(`GET /rooms/{id}/stream`, with a 15 s heartbeat), one `POST /rooms/{id}/{name}` per intent, the
catalog (`GET /rooms/{id}/events`) and, for a chat, the scroll-back (`GET /rooms/{id}/history`). The
lambda names the user from the call, null for anonymous. `rooms(build) { call -> user }` serves the same
routes for every room of a kind, `/rooms/{room}/…`: the room `Rooms` holds under that id, else `build(id)` (null:
404 `no_room`), which closes once idle. `LogicRoom(id, state, logic)` is the room for a logic; `ChatRoom`
adds the chat's history store. Either takes a `lobby` last: see [A lobby](#a-lobby-who-is-in-which-room).

- **Who the caller is, the room says**: `role(user)` answers `Role.ACTOR`, `Role.SPECTATOR` or null, asked of
  every call — the stream (an actor or a spectator; anonymous, at most a spectator), each intent (an actor's),
  the scroll-back and the catalog (anyone admitted). Null is `401 unauthenticated` for the anonymous, `403
  forbidden` otherwise, the stream's before it starts. It runs on the loop, the room's critical section: it reads
  the state (members, seats) as the step sees it, and must not wait. Signed-in callers act by default:
  ```kotlin
  override fun role(user: User?) = when (level) {
      PUBLIC  -> if (user != null) Role.ACTOR else Role.SPECTATOR
      OPEN    -> user?.let { Role.ACTOR }
      PRIVATE -> user?.takeIf { it.userId in state.members }?.let { Role.ACTOR }
  }
  ```
  An intent's role is asked in the step that applies it: a member removed, a seat taken, holds from the next
  intent. What an actor may do *now* (its turn, its seat) is the logic's `check`.
- **The door is stateless**: an unknown name, a bad shape, a field beyond the declared
  (`undeclared`), a missing permission (`permits(user, permission)`, the host's call) — refused
  before the loop, never queued.
- **The reply is the verdict**, nothing else: `200 {}` applied, `403 {reason}` for whom the room refuses
  (`spectator`, `forbidden`), `400 {reason, args}` rejected. A rejection is framed to nobody; the effects come
  down the stream, to the author like to everyone.
- **The instant.** A step of the loop numbers and writes everything it produces before it returns:
  a snapshot taken at a position holds exactly the frames up to it. Every frame's SSE `id` is its
  position on the room's channel.
- **No replay.** A reconnect is a join; the snapshot is the truth, first on every stream; an event
  only ever means a transition.
- **Presence is kroom's layer.** `users` is a keyed field of the base `State` (`{id, name, avatar,
  status, clients}`, the avatar as of the join), kept by `reduce` from `join`, `leave` and `status`; a user's status composes its
  clients' (`ONLINE`, else `IDLE`, else `AWAY`; `LOST` when none is left). The wire hears a user's
  first `join` and last `leave`, the leave after `leaveGrace` (1 s) so a reload is no departure. A
  room with a `lostGrace` says more: past `leaveGrace` the user is `LOST` (a `status` frame, the
  snapshot's `users` too, still counted), back before `lostGrace` is a `status` again (no `join`:
  they never left), `leave` after it — a phone in a tunnel, the same dot for every viewer; with
  `leaveGrace` 0 the room says `LOST` at the drop itself. Only the room says `LOST`: a client posting
  it is refused (`invalid`). The `leave` says where the user was when its last connection dropped:
  `seen`, the room's position (the SSE id of the last frame it may have received; the graces' frames,
  the `leave` itself, come after it), and `read`, the room's own numbering when it has one — a chat's
  last line, what a store marks the user read up to (`Room.read`, null by default). A logic hears every
  connection (`first`, `last` say which are a user's), and what it emits is framed whether the `join` or
  `leave` itself is or not; a connection gone beside another of its user is heard after the same graces, not
  at all when the tab is back within them — what a tab holds outlives a reload. A joiner is not in its
  own snapshot: its `join`, the same frame everyone gets, follows it. The snapshot also carries `me`,
  the viewer's own entry (absent for a spectator).
- **Guests are counted.** A connection without a user (an anonymous visitor's tab) is a guest: its `join`
  and its `leave` are framed without a `user`, and `guests`, a field of the base `State`, counts them (absent
  until the first). A guest is its connection: two tabs are two guests, and with no grace a reload is a
  `leave`, then a `join`.
- **Tabs.** A tab id is taken from `sessionStorage` at load and put back on `pagehide` (a duplicated
  tab makes its own); `?tabId=` on the stream, `X-Kroom-Tab` on an intent; the connection is
  `<userId>/<tabId>`, never a credential. A user's tabs see the same thing.
- **The loop never waits on a consumer**: a tab that stops draining is dropped and reconnects.
  `trace(event)` is the operator's line at every stage.
- **What the loop cannot decide, the store does, off the loop.** `persist(event)` gets every frame, copied on the
  loop, in the room's order, off the loop; the event goes `PERSISTED` after it. It is where a store writes (`ChatRoom`
  → `ChatHistory.append` for lines, `ChatHistory.persist` for the rest: a reaction never reaches the store before its
  line), and where a lookup the State cannot answer runs. Deleting a line older than the tail: the logic emits its
  question (an `event` entry, `USERS`: the author's tabs), `persist` reads the store and `act`s the answer (another
  `event` entry, so no client can forge it) as the request's author — an emission carries its intent's author —, and
  the logic decides on it like on any intent: checked, framed, or refused (the verdict is `act`'s, the app frames a
  late refusal to the author). So `200` is "taken into account", decided at once whenever the State is enough; what
  the store decides comes down the stream.

### In the browser (kroom-client)

The app compiles its common module to JS (one bundle) with its pages: velocity templates with typed
roots, compiled at build by the velocity gradle plugin, translated per language by kroom's l10n.

```
##@ state: ChifoumiState
##@ page: LobbyPage
##@output web
<p>$state.queue.size waiting</p>
#if($page.searching)<button data-kroom-intent="cancel" data-kroom-intent-of="match">Cancel</button>
#else<button data-kroom-intent="match">Find a match</button>#end
```
```kotlin
fun main() {
    val room = Room("lobby", ChifoumiState(), ChifoumiEvents, engine)
    room.page = LobbyPage(room)                                // what the page knows and no room does
    room.attach("en/lobby.html", document.getElementById("lobby")!!)
    room.on(ChifoumiEvents.chat) { scrollChatToBottom() }         // typed reaction to one name
    room.connect()                                             // <api>/rooms/lobby/stream
}
```

- **In the page's language**: a region compiled per language is named through the engine,
  `room.attach(engine.localized(pageLanguage()) { "$it/lobby.html" }, …)`: the page's language (`<html lang>`),
  else its primary tag (`fr` for `fr-BE`), else English — a page in a language its regions were not compiled for
  still has them. Dates are written in `localeOf(lang)`: plain `en` is `en-150`, English as Europe writes it (day
  first, 24 h), never the US's.
- **One path down**: each frame is folded into the State by the same `reduce`, then into the page
  memory, then every region is merged again; the engine's DOM sink touches only what differs (a
  value's text node, a dynamic tag's attributes, an item added, moved or removed by its key).
- **Targeted**: the fold records what it wrote (`State.dirty`: the `id` / `seq` of every view on a
  write's path, of an item removed from a `keyed` field too), and an item keyed by its identity that no write went under is clean — moved into
  place, its body not rendered. The rule: an item depends only on the writes under its own key (a
  line reading its author's name refreshes when the author is written); an item known by its
  position is never clean. A region is merged when a root its `##@` header declares changed
  (`state`, `page`: a write through their fields; `me`: a snapshot), so the header declares every
  root the region reads, through a page getter too (`round.html` reads the match off the state
  through `$page.round`: it declares `state`). A snapshot merges every region and renders every
  item, and so does `room.merge()`, the pass a page asks for itself (a timer writes nothing keyed,
  and "5 min ago" may sit in any item); `merge(all = false)` for a page that wrote through its
  fields. One rule for items and roots: a merge sees what went through the fields. A write past the
  views (`json[k] = v`, a plain Kotlin property) leaves no mark — a page memory is a State, its
  memory is fields.
- **A fault is its region's alone**: a region whose merge faults does not stop the others. Its root
  carries `data-kroom-broken` until its next good merge (the page's stylesheet says what a broken
  region looks like: `[data-kroom-broken] { … }`), `room.onFault` is told with the template and the
  fault (it logs by default), and the region is merged again when a root it reads changes — built
  whole, the engine's sink starting over after a fault.
- **Roots**: `state` (the viewer's view), `me` (its presence entry), `page` (page memory: a `State`
  of the page's own, folding every frame after the room's, with derived getters like `$page.phase`).
- **Intents in the markup**: `data-kroom-intent="play"` posts the event with the trigger's
  `data-kroom-<field>` values and its form's fields, each text in its declared kind (a number parsed,
  a checkbox true, an emptied field null, the key kept, an array its items separated by blanks:
  `data-kroom-ids="3 4"`); the trigger is busy for the span of the POST,
  on its own (a vote per line: another line's vote posts meanwhile, a second click on the same is swallowed).
  The intent's shape and its check run on the viewer's view first: a refusal there is shown like a
  reply's, without a request. A reason is a key a page may hold a sentence for
  (`#messages [data-kroom-msg="play:already_played"]`).
- **Presence from the tab**: `room.report(idleAfter)` (`kroom.report()` from plain JS) posts `status` on change —
  hidden → `AWAY`, no input for `idleAfter` ms (2 min) → `IDLE`, back → `ONLINE` — and again after a snapshot when
  not `ONLINE` (a reconnect is a join, started `ONLINE`); a spectator posts nothing, a page going away (`pagehide`)
  nothing either — its stream is closed then, opened again on `pageshow` (see Reconnect). It returns what stops it.
- From code: `room.act(Play(…))` (typed) or `act(type, payload)`; `on(entry) { event -> }` reacts to
  one name, `react { name, event -> }` to every frame (navigation, say). Reactions never emit.
- A plain-JS page gets the `kroom` facade: `kroom.connect()`, `kroom.act('status', {status})`,
  `kroom.on('state', fn)`, `kroom.say(key, vars)`.

### The example

`kroom-examples/chifoumi` is the proof: one `ChatRoom` lobby, matchmaking and rounds of
rock-paper-scissors-well, a round's moves selective fields of its match. `chifoumi-common` holds the
State, the events, `ChifoumiEvents`, `ChifoumiLogic` and the pages, compiled for the JVM and the browser.
`ChifoumiLogicTest` runs the decisions on both; `LobbyTest` records a whole match as one tab sees it
and checks the fold of it is the snapshot the room would serve; the recording is the golden that
`FoldTest` (JVM, node), `RegionsTest` (each region's DOM against its text render) and the pages' jsdom
harness replay.

### The `on()` path

`Room.on(::Play) { actor, play -> Reason? }`, a class and a handler that mutates the room's state and
emits itself, still runs underneath (`Table` is on it); its events register into the room's
`RoomEvents` as they are declared, and the class's `Audience` (`ROOM` / `USERS`) still applies.
`Route.intents { call -> user }` is the intent door for every room `Rooms` knows.

## Heartbeat and Dead Connection Detection

A stream the client closes (a tab gone, `EventSource.close()`, a proxy's `RST_STREAM`) is noticed at
once: `room(…)` installs ktor's `HttpRequestLifecycle` (`cancelCallOnClose`) on the stream route alone,
so the handler is cancelled, the room's `leave` runs and the graces count from the close — on Netty and
CIO; the other engines only fail at the next write. Nothing else is cancelled: an app's other calls
finish whether or not the client still waits. Note ktor logs no `CallLogging` line for a cancelled call:
the room's logger (`kroom.room`) says it, one line at info, `Stream of '…' closed by the client in room '…'`.

SSE connections can also die silently — browser killed, network dropped without TCP FIN. Ktor's SSE
heartbeat periodically writes a comment line to the socket; the write fails on a dead connection,
the handler ends and the room's `leave` runs. `room(…)` sets it on its stream (15 s). A frame written
to a closed tab ends the stream with one log line; a heartbeat written to one still logs ktor's
"Unhandled exception" trace (ktor launches its heartbeat writes unguarded). A stream route written by
hand sets the heartbeat (and the lifecycle plugin) itself:

```kotlin
sse("/my/stream") {
    heartbeat { period = 15.seconds }
    val channel = room.join(actor)
    try { for (event in channel) send(event) } finally { room.leave(actor) }
}
```

| Period | Detection | Notes |
|--------|-----------|-------|
| 2-5s | Fast | Good for interactive apps (games, chat) |
| 15s | Moderate | kroom's default |
| 30s | Ktor default | Sufficient for low-frequency updates |

## kroom-webapp-core

Ktor webapp foundation with `installCore()` plugin:

```kotlin
installCore {
    logLevel = Level.INFO  // CallLogging level

    static {
        // Default prefixes: css, js, img, fonts, lib, snd
        prefixes = listOf("css", "js", "img", "fonts", "lib", "snd")

        // Dev mode: serve from filesystem first, fallback to classpath; no-cache either way
        devMode = true
        devDir = File("src/main/resources/static")
        // or several, tried in order: sources, then a build output (a Kotlin/JS bundle)
        // devDirs = listOf(File("src/main/resources/static"), File("build/static"))
    }
}
```

An exception nobody caught is logged (`Unhandled exception`, with its trace) and answered 500 with a fixed
body. Two kinds are left to ktor, and are no error: a call its client left (a cancellation, ktor's line at
debug) and an exception ktor has a status for (`BadRequestException` 400, `NotFoundException` 404, 413, 415, 504).

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
| `api.js` | Fetch wrapper for REST APIs, `api.act` for intents |
| `sse.js` | EventSource wrapper: named events, reconnect, `into(store)` |
| `intents.js` | `[data-intent]` triggers, busy until the verdict (the reply) |
| `store.js` | Minimal Redux-like state management |
| `markdown.js` | The markdown toolbar (+ `markdown.css`): `kroomMarkdown.toolbar(textarea)`, `shortcut(e, textarea)` |
| `i18n.js` | `t(en, vars)`: a hand-written script's text through the page's catalog, `{name}` / `{0}` placeholders filled |
| `text.css` | The parts of a text written by several, under an element of class `kroom-text`: `KroomAssets.textStyle()` |

A page on a room built with kroom-client needs only `domhelper.js` and `api.js` (the tab id, the POST): the
stream, the intents and the State are the bundle's. `sse.js`, `intents.js` and `store.js` serve plain-JS pages.

`markdown.js` formats a textarea's selection: `kroomMarkdown.toolbar(target, {strings, icons, extra, language})`
answers a `div.kroom-format` of icon buttons (`target` a textarea, or a function answering the one to act on), and
`kroomMarkdown.shortcut(event, textarea)` handles Ctrl/Cmd+B, I, K; every edit goes through `replace(…)` and is
heard as `input`. Tags: `KroomAssets.markdownStyle()` and `markdownScript()` (after domhelper.js). The block
editor's toolbar is this one.

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
<script src="/js/kroom/sse.js"></script>
<script src="/js/kroom/store.js"></script>
<script src="/js/kroom/intents.js"></script>
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

// Intents (see above): fire and forget, the verdict comes down the stream
api.act('lobby', 'play', { move: 'rock' })   // POST rooms/lobby/play {move:'rock'}, X-Kroom-Tab
api.act('game/7/act', 'play', { move })      // a path of the app's own (holds a slash): {type:'play', move}
api.base                                     // where the API answers ('/api/', or kroomApiBase + '/api/')
api.tabId()                                  // this tab's id (sessionStorage)
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
rendered by a `#`-template page through `#markdown`. A block sits in its page's folder and sees only what
the page hands it, plus the tools named in `markdown.tools`:

```velocity
## pages/club/_code_.html
#markdown("description", {"club": $club})     ## pages/club/13Ma/description.md, $club in scope
```

```kotlin
engine.addMacro("markdown", MarkdownMacro(MarkdownConfig(
    loader = DirectoryLoader(Path("data/content")),     // or a kroom-webapp-authoring ResourceStore
    tools = listOf("math"),
)))
```

`#markdown` is a velocity 3.0 native macro: the page engine may be a 3.0 `VelocityEngine` or the classic
facade, the block renders on its own 3.0 engine either way. With `VelocityPlugin`, the same comes from
`markdown.*` properties (`markdown.loader`, `markdown.tools`, `markdown.acl`, `markdown.block.wrapper`, …).

Blocks are watched by default — strict mode, the full sandbox (`Sandbox.DEFAULT_ACL` plus `- write *` and
`- java.lang.Object#getClass`), a loop stopped at 10 000 turns (`directive.foreach.max_loops`; each loop its
own count), and of the 2.x conveniences only informal navigation, for prose. Relax any of it in `MarkdownConfig`.

A block keeps its html and loses its scripting: what it renders is sanitized against an allowlist, after
markdown, whoever typed it and wherever it came from.

```html
<div class="grid" id="top">…</div> <img src="/pictures/a.png" loading="lazy">     stays
<script>, <style>, <iframe>, <form>, onclick=…, style=…, data-…=…, javascript:…     goes
```

An address is `http(s):`, `mailto:`, `tel:` or relative; the `kroom-` classes and ids are the house's. What
needs more — an embed, a form — is a tool's business: a tool that answers a `SafeText`
(`org.apache.velocity.engine.SafeText`) has its markup set aside while the block is merged and put back as
written once the rest is safe, block element or inline.

Anything else a reference inserts is data, shown as typed, wherever it lands: a club named `<b>Lyon</b> *Go*`
shows those characters in a heading, a table cell, a link's text, an attribute. Markdown and html are the
block's own text, never a value's — a snippet to reuse is a `%define`, not a `%set` string. An address a value
makes (`[site]($club.site)`) is judged like any other.

Rendering is JVM-only on purpose: flexmark has no multiplatform build, server rendering is ktor/JVM
anyway, and kroom's multiplatform scope is model sharing, not rendering.

The markdown half stands alone in `kroom-markdown-core` (flexmark and jsoup, no velocity): `Markdown.html(source)`
is markdown to html through the same allowlist, for text that is no template.

## kroom-webapp-authoring

In-place editing of those blocks: the block a visitor reads is the block an author edits, through the same
tree — a `ResourceStore` is a velocity 3.0 `ResourceLoader` first, so a submit writes the very bytes the next
render reads.

```kotlin
val store = FileResourceStore(Path.of("/data/content"))  // or MemoryResourceStore(), or your own
installSessions { … }
installVelocity {
    // the engine is built at install, before authoring exists: the page side is wired here
    properties["markdown.loader"] = store                 // blocks read from the tree the editor writes
    properties["markdown.block.wrapper"] = "kroom/block_wrapper.html"
}
installAuthoring {
    this.store = store
    lockTimeout = 2.minutes                           // untouched that long, a block is free again
    apiPrefix = "/api/content"                        // must live under /api/ — api.js roots calls there
    identity = myDirectory                            // who is who, and their roles — see below
    placeholder = "*(nothing here yet)*"
}
```

`installContentSite { storage = …; identity = … }` does all of the above in one call, both template stacks included.
In dev, `devDir = File("src/main/resources")` serves that tree live instead of the classpath — layouts from its
`templates/`, static files from its `static/`, neither cached by the browser.

### The edit API

```
GET    {prefix}/{path...}[?rev=]   the block, its lock, whether the caller may edit it
POST   {prefix}/lock/{path...}     take the block — 409 names who holds it, re-entrant for its owner
DELETE {prefix}/lock/{path...}     give it back, unwritten
POST   {prefix}/{path...}          submit {page, rev, body} — 409 answers {message, theirs} on a stale rev,
                                   422 when the body breaks its page
POST   {prefix}/preview/{path...}  render {page, body}: the page itself, this body standing in
POST   {prefix}/shape/{path...}    render {page} to learn what the block sees — its first level
GET    {prefix}/shape/{path...}?at=club.membres()[]   the level that path leads to
GET    {prefix}/history/{path...}  revisions of one block   ] 404 unless the store is Versioned
GET    {prefix}/journal            the site-wide log        ]
```

401 without a session, 403 without `content.edit` on the block's path. The lock routes read `/lock/…` rather than `…/lock`
because a ktor tailcard takes every remaining segment. A submit carries the rev it started from, so an edit
made meanwhile — a concurrent author, a `git pull` — is answered with *theirs* instead of being overwritten:
the lock is the polite path, the rev check is the safe one.

The shape routes feed the editor's completion, and need the lock. What a block sees only exists in its page's
render, so `POST` renders the page once and the lock keeps the walk; each `.` then unfolds one level of
declared types under the block sandbox (velocity's `Navigation`) — what is offered is what a block may write.
A level maps each key to a leaf's type name, `{}` (unfolds) or `[x]` (iterates over x); `*` is any key of a
map, and a call's key spells its required parameters (`greet(name)`).

An error answers `{message, code, args}`: `message` in English, `code` (`lockHeld`, `stale`, `forbidden`,
`broken`, …) and `args` for a client that says it its own way — see *The editor's words* below.

**History and the journal exist only when the store is `Versioned`** — a plain `FileResourceStore` keeps no
past, and both routes answer 404; `GitResourceStore(root)` (module `kroom-webapp-authoring-git`, JGit) keeps it as
a git repository under the content root: a commit per write, deletion or move, authored by the block's author,
a block's past following it across a move. There is no `revert` route either: restoring an old body writes it as a
new revision through the ordinary submit, which keeps the journal honest.

### The page side

`markdown.block.wrapper` names a template the `#markdown` macro renders in place of the bare html, with
`$path`, `$name` and `$html` added to the page context. This module ships the default one at the classpath
root as `kroom/block_wrapper.html` — root, so it resolves under every engine shape (dev's `classpath` loader,
production's `root` loader) — and it emits the block plus, for an author `$authoring.canEdit` accepts, one
edit button. The editor markup itself is built by `authoring.js` when editing starts: a visitor downloads none
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

✎ takes the lock and opens the block in three tabs — while it is open, no block shows its handle; typing
refreshes the lock (debounced, 700ms), ✓ submits `{page, rev, body}`, ✗ gives the block back.

- **markdown** — the textarea, with formatting buttons (bold, italic, heading, link, lists, quote, code;
  Ctrl+B/I/K) that toggle their markup and go through the textarea's own undo stack.
- **🗑** — for a block the store holds: trash it, after a word of confirmation; a region it overrode falls back
  to the site's default, a versioned store keeps what went.
- **?** — a cheat sheet beside the editor (a manual popover: it stays while you type, Escape, × or ? close
  it), two pages: *markdown*, the syntax a block renders; *model*, the `%` directives and `$references`.
- **preview** — the page itself, re-rendered by the server with the textarea standing in for the stored
  block, of which this block's part is shown; rendered when the tab is shown, and only if the text moved.
- **history** — the unsaved draft, if any, then the store's revisions (none when it is not `Versioned`);
  picking a revision diffs it against what you are writing, and *restore* loads it into the markdown tab for
  you to submit — an undo is an edit like any other. ✓ and ✗ are hidden there.

**Drafts.** What is typed is kept in `localStorage` as it is typed — key `kroom.draft:<user>:<block path>`,
value `{rev, body, time}`, `rev` being the revision it started from — and dropped on submit or cancel. At page
load, a draft whose lock is still its author's (a reload mid-edit) reopens its block as it was; an older one
waits at the head of its block's history. A draft the block moved past meets the ordinary 409 on submit. The
leave-page warning only remains for a browser that refuses to store the draft.

A 409 on submit shows yours beside theirs, word-diffed, and you leave it editing against their revision,
keeping your text or taking theirs. A successful submit reloads the page: the submit answers a rev, and the
page is the only thing that knows how to render the block.

### The editor's words

The editor and the admin bar ship in English and French — every label, tooltip, status, help line and edit
API error code sits in one table at the top of each script, English the floor the other fills up from. Which
language they speak is the application's choice:

```kotlin
installContentSite { language = "fr" }                   // "auto" by default: the browser's first stocked language
```

`auto` reads `navigator.languages` and takes the first one kroom stocks, English otherwise. kroom's own words
are not up for rewording; what the admin bar says of a *plugin* is the plugin's, and the application's to
translate — `installContentSite { strings["forms.name"] = "Formulaires" }`, keyed by the plugin's ids (see
*The admin bar*). An error code the table lacks falls back to the server's English message.

The pictograms are the application's to redraw: `kroomAuthoring.icons` maps each button (`edit`, `bold`, …, `submit`,
`cancel`) to one SVG path on a 24px grid, stroked in the text's colour. CSS variables size and colour them:
`--kroom-icon-size` (1.5rem), `--kroom-icon-stroke` (1.75), `--kroom-edit-color`, `--kroom-submit-color`,
`--kroom-cancel-color`.

### Storage

Everything a site keeps, by kind, behind one interface — what kroom and its plugins write against:

```kotlin
interface Storage {
    val content: ResourceStore                                // the blocks
    fun settings(namespace: String): Settings                 // a plugin's configuration: small strings
    fun records(namespace: String, collection: String): Records   // rows it owns: submissions, subscribers
    val media: Media                                          // uploads, under names the store hands out
}
```

A namespace is a plugin id, so no plugin reads another's by accident. `MemoryStorage` (tests, demos) and
`FileStorage(Path.of("data"))` ship — the latter as plain files an operator can read and git can version:
`content/…`, `settings/<ns>.properties`, `records/<ns>/<collection>/<id>.json`, `media/<name>`. A real site
maps the four kinds onto what it runs: a git content tree, a database through skorm (a collection is a table's
rows), an object store.

**Media.** An editor picks, pastes or drops a picture or a PDF into the textarea; it is uploaded
(`POST {apiPrefix}/media`, `content.upload`) and written in as `![name](/media/<name>)`. What may be uploaded is
told by the bytes, never by the claimed type — PNG, JPEG, GIF, WebP, AVIF, PDF; no SVG, which served from the
site's origin is a stored XSS. A name is never reused, so `/media/<name>` is served to anyone as immutable.
`MemoryStorage` keeps media within a byte budget (100 MB by default) and refuses what would not fit rather than
forget a picture a page still shows; the admin bar lists and deletes them.

### Identity and roles

The provider knows people, the site knows what roles may do:

```kotlin
fun interface IdentityProvider {
    fun roles(session: UserSession): Set<String>                             // asked per call, never cached
    suspend fun authenticate(login: String, password: String): UserSession? = null   // null: login is a redirect
}
installContentSite {
    identity = MemoryIdentityProvider().user("admin", "admin", Roles.ADMIN)    // the demo's; or LDAP, OIDC claims…
    roles.grant("moderator", "forms.*")                   // admin: *; editor: content.*, pages.*; author: content.edit
    loginPage = "pages/login.html"                        // POST /login, /logout over the provider
    hiddenPages += "pages/player/_id_.html"               // served, and no part of the tree (see *Pages and the menu*)
}
```

Logged in, one is back where one was: serving its login page, the site remembers the page it was reached
from (a short-lived cookie), nothing in the address or the form. An application that knows better sets a
`from` field.

`Roles.can(roles, permission, target)` is open, for rights a flat table cannot say (an editor of one club's
pages only). kroom asks `content.edit` (target: the block path),
`pages.edit` (target: the page path) and `site.admin`.

### Plugins

A plugin is one object: an `id` that namespaces its settings, records, permissions and API, declared
`settings`, and `install(site)`, which only *registers* — `installContentSite` wires each piece where the
engines need it. The surface follows what WordPress's most installed plugins hook into (SEO, forms, caching,
security, analytics…), ranked by how many categories need each:

```kotlin
class Seo : Plugin {
    override val id = "seo"
    override val settings = listOf(Setting.textarea("description", "Description"))
    override fun install(site: Site) {
        site.head { call -> """<meta name="description" content="${htmlEscape(site.settings(this)["description"]!!)}">""" }
        site.routes { get("/sitemap.xml") { … site.pages() … } }
    }
}
installContentSite { plugins += Seo() }
```

A setting is made by the function naming its kind — `Setting.text`, `.textarea`, `.number`, `.boolean`,
`.secret` (never read back), `.choice(key, choices = listOf(…))` — each with a default, a help line and an
optional `group`, under which the admin form gathers it.

| `Site` call | for | WordPress |
|---|---|---|
| `settings(plugin)`, `records(plugin, c)` | configuration, owned rows | options, custom tables |
| `tool(name, value, blocks = true)` | `$name` in pages — and in `%` blocks; markup is answered as a `SafeText` | shortcodes |
| `routes { }` | public or guarded endpoints (`site.can`) | REST routes, admin-ajax |
| `requestTool(name) { call -> }` | `$name`, one value per request: what a page says of itself | `wp_title`-style filters |
| `intercept { call -> }` | before routing: redirects, firewall, cache | `template_redirect`, drop-ins |
| `notFound { call -> }` | what nothing answered: log it, or still answer it | `404_template` |
| `head { }`, `foot { }` | fragments at `$site.head()` / `$site.foot()` | `wp_head`, `wp_footer` |
| `admin(AdminEntry)` | a link or an application framed, as an entry of the admin bar; `{columns, rows}` tables, under the plugin in the plugins panel | `add_menu_page` |
| `onPublish { block, author -> }` | after each submit, off the request | `save_post` |
| `seed(layout) { page -> }` | what a page born with that layout starts as: the plugin's call in its content block | a post template |
| `every(period) { }` | scheduled jobs, from start to stop | WP-Cron |
| `grant(role, permissions)` | the plugin's permissions, given to roles | `add_cap` |

A page escapes what it inserts (below, *A value is data*): a tool's answer is data like any other, and a tool
that answers html says so by its type — `fun show(id: String): SafeText` — having escaped itself, with
`htmlEscape`, what it holds that someone typed.

A plugin installed is switched on and off live, from the plugins panel (`PUT /api/site/plugins/{id}/enabled`):
off, everything it recorded falls silent — fragments, routes (404), interceptors, listeners, jobs, its admin
entry, and its tools, so a block calling one fails and renders `broken`. Enabling asks the plugin's
`check(settings)` first: a plugin paired with a service (an SMTP relay, a search server) answers what is
missing and stays off until it is there. Themes are switched on the themes panel instead.

A layout owes its plugins two calls, `$site.head()` at the end of `<head>` and `$site.foot()` at the end of
`<body>` (see *Themes*). The examples, one artifact each under `kroom-webapp-authoring/plugins/`: **webmaster**
(the site as seen from outside: whether it wants indexing — robots.txt, a robots meta, `$page.noindex()` for
one page —, sitemap.xml over `site.pages()`, and every page walked on a schedule, every link and picture tried,
the broken ones listed with their page), **forms** (`$forms.contact()` in any block, a honeypot, messages as
records for `forms.read`, daily retention), **gallery** (photo galleries: a record of pictures in order with a
caption per language, shown wherever a block calls `$gallery.show("k7f3a")` as a grid of squares that opens a
slideshow — swipe, keys, autoplay, deep links `#<id>-<n>`, zoom and download of the original — and edited in
place by `gallery.edit` holders: upload or drop, drag to reorder, caption, remove, every change one `PUT
/api/gallery/{id}` of the whole list; a page born with the `gallery` layout is seeded with a gallery of its own;
renditions through `kroom-webapp-images` when the application hands a `Gallery(Images(local = File("data/media"),
path = "/pictures", …))`, the originals otherwise). Mail is the site's, not a plugin's: `site.mailer` is SMTP over the
site's *Mail* settings once a host is set (the same `Mailer` kroom-webapp-auth sends its codes through), or the
transport the application set; forms mails each message to its `notify` address through it.

What the site says of itself is not a plugin's: its card — description, Open Graph tags, canonical URL — is
rendered by kroom in every head from the site's settings, a page's own words over them (`$page.title(…)`,
`$page.description(…)`, `$page.image(…)` before `$site.head()`); its redirect rules (`/old /new [301]`,
`{name}` captures in the path and the query, `@resolver` for what only the application knows —
`installContentSite { redirectResolvers["player"] = { … } }`) answer before routing, and the 404s are counted,
both under the *site* admin entry.

### Themes

A page says *what* it is and hands its parts down as closures — Velocity's `#define`, rendered where the
layout places them, straight to the response, nothing buffered — then names its layout:

```velocity
#set($title = "Les Vagabonds")
$page.description("Un club de go à Marseille")
#define($east) <article>…</article> #end
#define($content)
  <h1>Les Vagabonds</h1>
  #markdown("description", {"club": $club})
#end
#layout("article")
```

The regions are `$header`, `$top`, `$content`, `$east`, `$west`, `$footer`; a region nobody defined is not
there, so a side column is an `$east` written and a page opening on a band a `$top`. The layouts say what no
region can — how wide the content runs, and whether the section's menu stands beside it: `article` (a reading
column, the menu on its left), `wide` (the whole width, no menu), `gallery` (a page that is a gallery: a plugin
seeds its call at birth; the menu, and all the width beside it). On a small screen the basic theme lays the
menu out as one line that scrolls sideways. `#layout()` takes the site's `layout` setting, `article` until it says
otherwise. `$header`, `$west` and `$footer` have a
site-wide default a page overrides by defining them: the brand, the menu and the way in; the section the
visitor is in, its pages listed; the site's `footer` line. Under the header, a breadcrumb (`$nav.trail`: the
site, the section, the page) shows on any page but the home page — a region partial like the others.

Two kinds of theme, both `Theme : Plugin`, several installed, one active (the `theme` site setting, switched
from the admin bar, live; an admin previews another with `?theme=<id>`):

- a **skin** (`Skin`) names its `stylesheets` (and `scripts`) and renders kroom's own skeleton,
  `kroom/skeleton.html` — every layout in one file, `data-layout` and `layout-<name>` for the CSS, each
  region through a partial the skin may rewrite: `themes/<id>/regions/<region>.html` over
  `kroom/regions/<region>.html`. kroom's `BasicTheme` is one: pico and a few lines of CSS.
- a **markup-owning** theme provides its `layouts` under `themes/<id>/layouts/<name>.html` (`article`
  required, a missing one falls back to it), rendering `$content` and the regions it shows (`#if($east)` tests
  without rendering), `$title`, `$site.name`, `$site.head()` at the end of `<head>` and `$site.foot()` at the
  end of `<body>` — all kroom and its plugins ask.

Both get `$nav` (the menu, see below; `#nav($nav.items)` renders the tree with `aria-current`, a draft
marked for its editors, each entry's description in a `<small>` for a theme that shows
panels; `$nav.section` is the top-level entry the visitor is under) and `$theme` (the
theme: `id`, `name`, `stylesheets`, `scripts`, `settings`, and any setting by key — `$theme.scheme`). A theme
adds to `<head>` like any plugin, `site.head { }` in its `install`, emitted while it is the active one. Assets
live under `static/{css,js,img,fonts}/<id>/`, private partials under `themes/<id>/inc/`. The authoring tests
hold a `DummyTheme` — every layout of the vocabulary, plainly and unmistakably — for testing a theme's path
end to end; the demo installs it beside the basic one.

A miss is a page of the site too: what no route, no page and no plugin's `notFound` answered gets
`kroom/not_found.html` in the active theme, with a 404 — `notFoundTemplate` names the site's own, and an
API's or an asset's miss keeps the plain answer.

### A value is data

`installVelocity` switches velocity's dynamic escaping on: in a web page (`.html`, or a template whose header
says `##@output web`) every value a reference inserts is escaped for where it lands.

```html
<title>$title — $site.name</title>              text up to the closing tag: `<` and `&`
<a href="$item.href">$item.label</a>            an attribute value, then text
<script>const user = $logged.name;</script>     a quoted JS literal
$site.head()                                    a SafeText: markup, as it stands
```

Markup is `org.apache.velocity.engine.SafeText`, and nothing else is: what `#markdown` renders, `$site.head()`,
`$site.foot()`, a plugin's tool that answers html. A region a page `#define`s is written where the layout calls
it, so its own references are escaped there. A reference in a place no escaping makes safe (a tag name, an
unquoted attribute) is refused with its position. An application that wants another handler, or none, names it
in `installVelocity { properties["event_handler.reference_insertion.class"] = … }`. A URL attribute is an
ordinary one: the scheme of an address someone typed is checked where it is stored (a menu link is `http(s):`,
`mailto:` or `tel:`).

### Pages and the menu

The pages are a tree, and the tree is the menu: an entry is a page's segment under its parent, so the tree of
entries *is* the tree of URLs (`company` at the root is `/company`, `history` under it `/company/history`;
`company.html` and `company/index.html` are one page). What exists is the developers' templates — and the
instances of their placeholder pages — plus the pages editors made. An editor's stored arrangement only orders
and words them (`MenuItem`: `slug`, `label` and `description` by language, `children`); it never makes a page
exist: a page made since appears after the arranged ones, an entry no page answers any more is dropped. Until
one is stored, the tree is derived from the pages, a deeper URL nesting under its first segment.

**No entry without a page.** Every entry is a page (or a link), so a click leads where it says. Once the
application has started, kroom completes the tree: a path with pages under it and none of its own — `/legal` over
`pages/legal/terms.html`, `/club` over the placeholder `pages/club/_club_.html` — gets an authored page like any
other, `wide`, signed `kroom`, its content its name as a heading and the list of the pages under it
(`AuthoredPage.TOC`, a loop over the `$children` `kroom/page.html` hands the block). It is out when visitors
already reach something under it, or when nothing is there yet; a draft when all under it is drafts. It only
fills gaps: what an editor makes of it is theirs. An address where a placeholder binds a value (`/club/13Ma`
when only `pages/club/_code_/members.html` exists) is left alone: it answers 404, and is in no menu, nor what is
under it. The API keeps the tree whole: a page is created or moved only under a page (409 `noParent`; a
top-level one needs none); a page with pages under it is not deleted (409 `pageHolds`: an editor's page, a
visitor's URL or a template's route), nor unpublished while visitors reach one of them; publishing a page
publishes the drafts above it.

**In no menu, and somewhere all the same.** A page the menu does not hold — a record a database lists, a template
kept out of the tree, an instance nobody wrote — has its trail (`$nav.trail`, the breadcrumb, `$nav.section` and
so the west) from the nearest page above it that is in the menu, then itself, named by its segment:
`/club/9-zz` reads *the site, Club, 9 zz*. With nothing above it in the menu, it has none.

**Out of the tree.** A template the application names in `hiddenPages` is served and is no part of the tree:
what it answers is in no menu and no sitemap, and nothing is made above it — the pages a database lists by the
thousand (`pages/player/_id_.html`), reached from the page that lists them. The login page is one already.

**Under a developer's placeholder.** A page an editor makes where a placeholder template answers — `/club/9Zz`
under `pages/club/_club_.html` — is the template's: the developer chose how it is laid out, the editor writes its
blocks. Its record says it is there, by the name it was given, and whether it is out: a draft is in no visitor's
menu (its address answers what the template answers, as any other there). It has no layout to choose, does not
move, and is deleted with its blocks, by which such a page is there.

The one entry that is no page is a link (`href`, anything with a scheme — `https://…`, `mailto:…`), which exists by
being stored: it has words and a place in the tree, never a page under it, is never the current page, and a
theme gets it with `external` (`#nav` adds `rel="external"`). In the panel the new-entry form has one input
for both — a segment makes a page, an address a link.

A page's label is its title, its description the page's — one field each, by language, so the menu and the page
cannot disagree: the site's `lang` is the default, `languages` the others it speaks, and a request's language
is what `installContentSite { requestLanguage = { it.language } }` answers (kroom-webapp-l10n's, or the
application's), the default filling what a language lacks.

**Authored pages.** A page need not be a template: an editor makes one, a record (`path`, `layout`,
`draft`|`published`), and kroom renders it through one template, `kroom/page.html` (`installContentSite { pageTemplate = … }`
names an application's own — one that picks its blocks per language, say; it hands the content block `title`
and `children`, which the list of a page made to complete the tree reads), each region a block in the
content store right where a template's would be (`pages/company/history/content.md`), edited in place like any
other, previewed within its page. Authored pages answer after the templates, in the not-found phase: a
developer's page wins, an editor cannot take `/login`. Deleting the record leaves its blocks in the store.

A region is what its block makes it. Written, everyone sees it. Not written, a visitor sees nothing there,
and whoever may write it finds a sliver in its place, unseen until hovered, that says what it is (*right
sidebar*) and offers the edit handle. Trashing the block (the editor's trash, `DELETE /api/content/{path}`)
brings the emptiness back. Header, west and footer are the site's, never a page's: the theme's partials render
them, the `site.name` and `site.footer` settings word them.

A page is a `draft` until published: its editors see it, marked *draft* in the menu; visitors and the sitemap
do not.

**Moving.** An authored page with no page under it moves by being dragged under another parent — its record,
its blocks with their history (`ResourceStore.moveAll`), its words. The move is refused when a page (or a
placeholder template) already answers the destination, when no page stands above the destination, when the
page has pages under it, or when someone else
is writing one of its blocks. A developer's page is reordered among its siblings, not moved: its address is
its template's. No redirect is written from the old address; the not-found table of the *site* entry shows
who still asks for it.

**Rendering.** Three site settings shape the menu a visitor gets: `menuDepth` (how deep the header's menu goes,
2 by default), `menuPanels` (the header shows a section's pages as a hover panel) and `west` (the section's
pages on the left by default, or nothing).

**The *pages* entry** of the admin bar is that tree, compact, one line per page — a handle to drag it by
(SortableJS; a phantom row shows where it lands, a page's own list, empty, is how it gets a first child;
a developer's handle is greyed) and its label, which opens an accordion: the label and description in the
chosen language, the page itself, and for an editor's page its layout,
publish/unpublish, delete. A new page is born where it goes: a phantom row under the tree, dragged into place,
asks its name — the segment derived from it (`Notre Société` → `notre-societe`) until typed over, an address
there instead makes a link — and the page is created under that list, at that very index. A page is born
empty, unless its layout has a seed (a gallery's). Every gesture stores the tree and reloads the page — the menu
and the west are server-rendered — and the bar reopens where it was (the panel and the entry, kept per tab); a newborn page is
opened, to be written.

`GET`/`PUT`/`DELETE /api/site/menu` (the tree; its arrangement; forgetting it), `POST /api/site/pages`
(`{path, label, layout, status}`, a draft unless said, under a page), `POST /api/site/pages/move` (`{from, to}`),
`PUT`/`DELETE /api/site/pages/{path}` (layout, status), all for `pages.edit` — the editor's; an `author` writes
blocks and nothing else. An application computing its own menu sets `navigation = { call -> List<NavItem> }` over all this.

### The admin bar

`$site.foot()` emits, for whoever may open at least one of its entries (*pages* asks `pages.edit`, the others
`site.admin`), a bar on the left of the page — the site's own settings (below), pages (the tree, see *Pages and
the menu*), plugins (each with its switch, its settings form, a secret never read back, and its tables),
themes (each with its preview and its settings, set whether worn or not; the entry shows when there is a choice
or something to set), roles, then the plugins' entries that stand alone — a link, an
application framed; tables alone show under their plugin, in the plugins panel — and the eye: pressed, the page as
visitors see it, released, every block outlined. A panel action that changes what the page shows reloads it, and
the bar reopens where it was. Switching off a plugin that `holds(site)` something ("3 galleries, 41 pictures")
asks first. The journal has no entry for now (its API stays). Its data comes from
`/api/site/{settings, menu, pages, plugins, plugins/{id}/settings, themes, roles}`; its markup is built by `admin.js`. It speaks the editor's language (*The editor's words*); a plugin's
words are the application's to translate, `installContentSite { strings[…] }` keyed by the plugin's ids (`<entry id>`,
`<entry id>.<table id>`, `<plugin>.name`, `<plugin>.description`, `<plugin>.<setting>`, `<plugin>.<setting>.help`,
`<plugin>.<group>`), defaulting to what the plugin says. Pictograms: `kroomAdmin.icons`.

### Warm-up

Interpreted templates cost their compile the first time — the runtime compiler's start, then each template's —,
several seconds on a fresh JVM. Once the server listens, a content site asks itself for `/` in the background,
the whole way through (layout, regions, blocks), and logs how long it took; the first visitor finds the home
page warm. `installContentSite { warmUp = false }` turns it off; other pages still pay their own first compile.

### The site's settings

The site is configured like a plugin, from the admin bar's first entry: kroom asks what every site is asked —
*Site* (`name`, `lang` and the other `languages`, `baseUrl`, `description`, `image`, the default `layout`, a
`footer` line, `menuDepth`, `menuPanels`, `west`), *Mail* (`smtpHost`, `smtpPort`, `smtpSecurity`, `smtpUser`,
`smtpPassword`, `mailFrom`), *Redirects* (`redirects`) — and the application appends its own:

```kotlin
installContentSite { settings += Setting.text("motto", "Motto", default = "festina lente") }
```

Stored under the `site` settings namespace, defaults showing through; `GET`/`PUT /api/site/settings`. A
layout or a block reads them as `$site.name`, `$site.lang`, `$site.baseUrl`, `$site.description`, `$site.image`,
and `$site.settings.motto` for the application's. A theme has settings of its own (`$theme`) for what is the
look's: colours, a footer line — not the site's name.

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

## A lobby: who is in which room

A `Lobby` (kroom-server) is a room of rooms: the scope of a group of rooms, such as a community. A page opens one
stream on the lobby and sees, live, who is in each room of the group, with no stream per room. A room names its
lobby at construction, and what its own wire says of presence is said there again.

```
state    {users, rooms: {<room id>: roster}, me}            the watcher's snapshot: the rooms that show
show     {roster: {id, users, guests}}                      a room shows, its roster whole
enter    {room, user: {id, name, avatar, status, clients}}  the room's join: a user's first connection there
present  {room, user, status}                               the room's status
exit     {room, user}                                       the room's leave: a user's last connection, past the graces
enter    {room}, exit {room}                                a guest's join and leave: no user, the roster's guests ± 1
hide     {room}                                             the room closed, or no longer shows
```

A lobby is a `LogicRoom` held in `Rooms` like any room, and served the same way. A room's `build` may fetch its
lobby through `Rooms.getOrCreate`, then pass it as the constructor's last argument:

```kotlin
class Den(id: String, lobby: Lobby<*, *>?, @Volatile var hidden: Boolean = false) : LogicRoom<DenState>(id, DenState(), DenLogic, lobby) {
    override fun show(roster: Roster) = !hidden         // asked on the room's loop
}

rooms({ id ->
    if (id.startsWith("community-")) Lobby(id)
    else Den(id, Rooms.getOrCreate("community-${communityOf(id)}") { Lobby(it) } as Lobby<*, *>)
}) { call -> userOf(call) }
```

- A room's roster is born by a `show` frame when the room gets its first user or guest, and removed by a `hide`
  frame when the room closes. In between it may be empty.
- A roster's `guests` is its room's count of connections without a user. The lobby's own `guests` counts its own
  anonymous watchers.
- A room whose `show(roster)` answers false leaves no trace in the lobby: no roster, no id, no frame. An app's own
  event about that room is dropped too.
- `announce()` asks `show` again: call it after the room's access changed. A `show` or a `hide` frame follows.
- The lobby's `role(user)` says who watches, as any room's. Every frame goes to every watcher; the base's `users`
  is who watches.
- A lobby built on demand does not close while one of its rooms is live.
- `Lobby.rooms` lists every live room of the group that `Rooms` holds, shown or not. It is on the server alone,
  never in the State.

An app's rosters say more by extending `Roster`, `LobbyState` and `LobbyEvents`. Here a roster lists who is in the
room's call:

```kotlin
class Caller(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) { var id by string().required }
class Voiced(json: Json.MutableObject = Json.MutableObject()) : Roster(json) { val call by keyed(::Caller) }
class Listen(json: Json.MutableObject = Json.MutableObject()) : RosterEvent("listen", json) { var user by string().required.out }

object VoicedEvents : LobbyEvents<Voiced>(::Voiced) { val listen = event(::Listen) }
class VoicedState : LobbyState<Voiced>(roster = ::Voiced) {
    override fun reduce(event: Event) {
        super.reduce(event)
        if (event is Listen) rooms[event.room]?.call?.add(Caller().also { it.id = event.user })
    }
}

val lobby = Lobby("community-go", VoicedState(), LobbyLogic<VoicedState>(VoicedEvents))
lobby.emit(Listen().also { it.room = "den-1"; it.user = "alice" })
```

A room fills its own fields in `show`: the roster it gets is of the lobby's class (`roster as Voiced`). The host
emits on the lobby what it wants, and `.to(userId)` makes a message for one watcher.

In the browser, a lobby is an ordinary kroom-client room:

```kotlin
val room = Room("community-go", LobbyState(), LobbyEvents(), engine)     // or VoicedState(), VoicedEvents
room.connect()                                                           // <api>/rooms/community-go/stream
```

Only rooms that show to every watcher are covered. Showing a private room to its members alone is not built.

## Reconnect

`EventSource` reconnects on its own; a page can also `close()` the `Stream` that `connect()` returned
and `connect()` again. Either way the server sees a join and answers with the snapshot: there is no
replay buffer, anything a client must not miss belongs in the State, and one stream in order needs no
dedupe. The stream follows the page's life: `pagehide` closes it — a page parked in the back/forward
cache keeps its `EventSource` open otherwise, and the server only hears the drop when the browser
discards the page (60 s in Chrome), the user listed present in a room they left —, `pageshow` opens it
again with the same tab id (back within the graces: a `status`, no `leave`, no `join`). A reload's
`pagehide` is the close the unload would have made. A `Stream` closed by the page stays closed.

## Chat history

`ChatRoom(id, history: ChatHistory<Chat> = MemoryChatHistory())` keeps a chat beyond the State's tail. The logic
numbers a line (`seq` = the State's `last` + 1), the State keeps the last 50 — what a snapshot carries; `max = 0`
keeps none, an app's State may keep them in another order (a tree) —, the room frames it, then hands it to the
store in the background: the loop never waits on a store. The store is asked on its own terms: `last()` and
`tail(n)` when the room starts, to seed the sequence and the tail, and `before(seq, n)` for
scroll-back, which `room(…)` serves as `GET /rooms/{room}/history?before=&n=` (`{messages: [...]}`, oldest
first, each line as the reader's snapshot shows it; `rooms(…)` for every room of a kind); `after(seq, n)` and
`?after=` read on from a line jumped to (a reply's source, a search hit); both bounds together are the lines strictly
between. The route's one call is `between(after, before, n, params)`, by default over those two; `params` are the
request's other query parameters, for a store with more to say (a span, a ranking). A line carries its `author` (`Author(id, name, avatar)`): the user's id and avatar (`User.avatar`),
or an external one once a bridge speaks for someone else (`sendChat(author, text)`). Write a `ChatHistory` over
your database and hand it to the room; `MemoryChatHistory` keeps the last 1000 and dies with the process.

A line is one class from the frame to the store: extend `Chat` and every chat type carries it, so a field declared
once (reactions, edits) is in the frame, the tail, the snapshot, the store and the scroll-back, and its visibility
holds everywhere:

```kotlin
class Line(json: Json.MutableObject = Json.MutableObject()) : Chat(json) {
    var reactions by keyed(::Reaction).out           // filled by the app's logic or its store, never posted
}
object Events : ChatEvents<Line>(::Line)
val room = ChatRoom("lounge", DbChatHistory(), ChatState(line = ::Line), ChatLogic(Clock.System, Events))
```

The room hands the store copies and seeds the tail with copies: a fold changing a line of the tail never races a
store reading its own.

## A text written by several

`TextState` / `TextEvents` / `TextLogic` (kroom-common) are a text several people write at once, in a plain
`LogicRoom`: its parts in order (`parts`, a `seq` of `Part(id, text, holder, client, caret)`), one writer per part.

```kotlin
val room = LogicRoom("proposal", TextState(stored), TextLogic())
```

```
take    {ids}              free, next to each other     they become one part, held             held · gone · scattered
take    {after}            a part that is there         a part is born after it, held          gone
write   {id, text, caret}  the part held                its text, whole, and where one is      not_held · gone
release {id}               the part held                cut into its paragraphs, free          not_held · gone
```

The page computes nothing. An intent names parts a frame told it of and says what was done; the fold alone decides
what the text is made of, the same on the server and in the browser: the parts taken become one (the first, saying
what they all said), the part given back is cut at its blank lines (`Part.cut`, a fenced block kept whole) — its
first paragraph keeps the id, the others are born after it (`state.last`), left blank it goes, the text's last one
stays. A take is "I go there": what the client held before is given back by the same frame, so going from one
paragraph to the next, or beginning one (`after`), is one step and a client never holds two. A view that is late is
refused, never applied askew, and a frame is a state, never a difference: two writes in a row say the same part
twice, nothing is replayed, a snapshot is the text.

The page reacts: a click posts `take`, and it is the frame, folded, that gives its tab something to write in — the
part rendered as a textarea for the client that holds it (`part.client`, a tab: the same user's other tab is
refused like anyone); the others see its text follow each write, the holder's mark at `part.caret` (`part.head`,
`part.tail`). Two people write two parts at once; one part is one writer's, in order. Everything selected and
pasted over is a `take` of every part, refused while anyone holds one. What a client held is released when its
leave is heard, after the room's graces: a reload keeps its part, a tab closed gives it back.

Who may write is the room's to say, in its own take: a subclass of `Take` whose check reads the room's State, run
after the text's, in the browser before the post and on the loop. A text that closes gives back what is held: its
logic emits a `Release` per part, as `TextLogic` does when a tab leaves.

```kotlin
class Draft(json: Json.MutableObject = Json.MutableObject()) : Take(json) {
    init { check { state: Proposal, actor -> if (state.closed) Reason("closed") else if (actor.id !in state.editors) Reason("not_editor") else null } }
}
object ProposalEvents : TextEvents(::Draft) { val close = intent(::Close) }
object ProposalLogic : TextLogic<Proposal>(ProposalEvents) {
    init { on(ProposalEvents.close) { state, _, _ -> Emit(state.parts.filter { !it.free }.map { Release(it.id) }) } }
}
```

In the browser (kroom-client), `room.write(template, root)` is the region and its `Writer`: the textarea of the part
held posts `write` as it is typed, a line left blank then more posts `take {after}` (what is typed while the part
is on its way goes into its textarea when it comes), leaving the textarea or five seconds without touching it
posts `release`. A tab says one thing at a time, in the order it did them: a click on another part waits for the
reply to the last `write`. `TextPage` is the page memory (`$page.mine($part)`, `$page.holder($part)`), open for a
page that remembers more:

```html
##@ state: com.republicate.kroom.event.TextState
##@ page: com.republicate.kroom.client.TextPage
##@output web
#foreach($part in $state.parts)
#if($page.mine($part))
<textarea data-kroom-part="$part.id">$part.text</textarea>
#elseif($part.free)
<p data-kroom-intent="take" data-kroom-ids="$part.id">$part.text</p>
#else
<p class="held" data-kroom-intent="take" data-kroom-ids="$part.id" data-holder="$page.holder($part)">$part.head<i></i>$part.tail</p>
#end
#end
```

A click on a part someone holds is refused on the page by the check, without a POST, and shown like any refusal.
That region is `kroom/text.html`, kroom-client-text's, compiled under velocity's prefix `kroom` for the browser
alone; its parts are styled by kroom-webapp-assets' `text.css` under an element of class `kroom-text`. A page gives
its engine kroom's registry beside its own and writes in the region, or parses it from a region of its own:

```kotlin
val engine = VelocityEngine(null, Config(informalNavigation = true)).apply {
    addResourceLoader("stock", StockResourceLoader(velocity.templates.mine.VelocityTemplates::resolve, velocity.templates.kroom.VelocityTemplates::resolve))
}
room.write("kroom/text.html", root)          // or room.write("mine/proposal.html", root), which says #parse("kroom/text.html")
```

Modules sharing an engine are built with the same settings (`compat.informal_navigation` here). The region is not
in kroom-client, which has a JVM target: compiled templates publish the settings they were built with, and every
engine on a JVM classpath takes them (`kroom-plugin-scratchpad`, in the authoring demo at `/scratchpad`, is the
worked example).
`state.text` is the text the parts make, `TextState(text)` the state a room is rebuilt from when a store kept that
text. The room stores nothing — override `persist(event)` for that.

A text shares its page with another room (a proposal over its discussion): each `Room` has its stream, under the
one tab id, and its regions' triggers post to it. Their roots must not nest — a region owns what is under its root,
and a click is heard by every root above it; presence is each room's (`report()` on both); the `kroom` JS facade
holds one room.

## Images

`kroom-webapp-images` serves images resized by an [imgproxy](https://imgproxy.net) container and kept on disk, from
local files and remote sources alike, so a viewer only ever hits your server:

```kotlin
val images = Images(imgproxy = "http://app-img:8080", cache = File("data/cache/img"), local = File("data/img"))
// beside kroom-webapp-core's static files, give it a `path` of its own: `/img` is theirs (static/img/)
routing { images(images) }                                   // GET /img/{path...}?size=
images.url("avatars/u1.png", 64)                             // /img/avatars/u1.png?size=64
images.url("https://cdn.discordapp.com/avatars/…/….png", 64)  // /img/~/<signature>/<source>?size=64
```

A size snaps up to a rendition, `fill` (cropped square) 32/64/128/300, `fit` 600/900, the original beyond or at 0;
renditions are webp, kept under `cache` by source and rendition, made again when a local file is newer or after
`remoteTtl` (7 days) for a remote one. imgproxy failing, the original is served; a remote unreachable, its last copy.
A remote source is served only under this server's signature (HMAC, key in `cache/secret` unless given), so the
route is no open proxy; a remote original must be a raster image (no SVG). Guard the route like any other by
mounting it inside your own. On the imgproxy side: private network only, `IMGPROXY_LOCAL_FILESYSTEM_ROOT` = the
`local` directory as mounted there, and optionally `IMGPROXY_ALLOWED_SOURCES`.

## Starting a site

From a machine with Docker and nothing else:

```
sh -c "$(curl -fsSL https://republicate.com/kroom/create.sh)"
```

The starter asks for a name, a folder, the languages, a contact email (given, the contact form is on and its
messages go there) and a port, then writes the site: a Kotlin build pinned to the kroom it came from (and to
its velocity engine, both published on `republicate.com/maven2`), one `Main.kt` around `installContentSite` —
a `FileStorage` under `data/`, the basic theme, the webmaster plugin, an admin whose password is in `.env` —,
two pages, a `compose.yml` and a `run.sh`, and a README. `./run.sh` is `docker compose up`: the plain JDK
image, the site's folder mounted at the same path, run as the user who created it (`UID`/`GID` in `.env`),
building with the site's own wrapper, then running the installed distribution — no image of its own, there is
nothing to customize in it; `./run.sh dev` adds Mailpit. The starter is `kroom-webapp-authoring-starter`, a jar of
the standard library and the templatized files under `starter/`; `create.sh` fetches the latest and runs it in
a bare JRE container, as you, in the current directory. `publish-snapshot.sh` publishes both.

## Run Examples

```bash
./gradlew :kroom-server:run           # SSE playground at :8080/playground
./gradlew :kroom-examples:chifoumi:run  # Rock-paper-scissors-well at :8080, two browsers to play

# the authoring demo, every example plugin installed, a Mailpit mailbox beside it to read what the site sends — admin / admin
KROOM_UID=$(id -u) KROOM_GID=$(id -g) docker compose -f kroom-webapp-authoring/demo/compose.yml up
#   http://localhost:8099/login, Mailpit on :8025 (KROOM_DEMO_PORT, KROOM_MAILPIT_PORT to move them)
./gradlew :kroom-webapp-authoring-demo:run -Pport=8099                # the same without docker, and without mail
```

## License

Apache 2.0
