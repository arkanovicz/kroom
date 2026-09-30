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
kroom-webapp-authoring in-place block editing, storage/identity APIs, plugins, admin bar
kroom-plugin-*        example plugins (kroom-webapp-authoring/plugins/): webmaster, forms, analytics, webhook, mail, dummy-theme
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

        // Dev mode: serve from filesystem first, fallback to classpath; no-cache either way
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

Blocks are watched by default — strict mode, the full sandbox (`Sandbox.DEFAULT_ACL` plus `- write *`), and
of the 2.x conveniences only informal navigation, for prose. Relax any of it in `MarkdownConfig`.

Rendering is JVM-only on purpose: flexmark has no multiplatform build, server rendering is ktor/JVM
anyway, and kroom's multiplatform scope is model sharing, not rendering.

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
    properties["markdown.block.wrapper"] = "kroom/block-wrapper.html"
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
GET    {prefix}/history/{path...}  revisions of one block   ] 404 unless the store is Versioned
GET    {prefix}/journal            the site-wide log        ]
```

401 without a session, 403 without `content.edit` on the block's path. The lock routes read `/lock/…` rather than `…/lock`
because a ktor tailcard takes every remaining segment. A submit carries the rev it started from, so an edit
made meanwhile — a concurrent author, a `git pull` — is answered with *theirs* instead of being overwritten:
the lock is the polite path, the rev check is the safe one.

An error answers `{message, code, args}`: `message` in English, `code` (`lockHeld`, `stale`, `forbidden`,
`broken`, …) and `args` for a client that says it its own way — see *The editor's words* below.

**History and the journal exist only when the store is `Versioned`** — a plain `FileResourceStore` keeps no
past, and both routes answer 404. There is no `revert` route either: restoring an old body writes it as a
new revision through the ordinary submit, which keeps the journal honest.

### The page side

`markdown.block.wrapper` names a template the `#markdown` macro renders in place of the bare html, with
`$path`, `$name` and `$html` added to the page context. This module ships the default one at the classpath
root as `kroom/block-wrapper.html` — root, so it resolves under every engine shape (dev's `classpath` loader,
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
    roles.grant("moderator", "forms.*")                   // admin: *, editor: content.* by default
    loginPage = "pages/login.html"                        // POST /login, /logout over the provider
}
```

`Roles.can(roles, permission, target)` is open, for rights a flat table cannot say (an editor of one club's
pages only). kroom asks `content.edit` (target: the block path) and `site.admin`.

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
| `tool(name, value, blocks = true)` | `$name` in pages — and in `%` blocks | shortcodes |
| `routes { }` | public or guarded endpoints (`site.can`) | REST routes, admin-ajax |
| `requestTool(name) { call -> }` | `$name`, one value per request: what a page says of itself | `wp_title`-style filters |
| `intercept { call -> }` | before routing: redirects, firewall, cache | `template_redirect`, drop-ins |
| `notFound { call -> }` | what nothing answered: log it, or still answer it | `404_template` |
| `head { }`, `foot { }` | fragments at `$site.head()` / `$site.foot()` | `wp_head`, `wp_footer` |
| `admin(AdminEntry)` | an entry of the admin bar: a link, `{columns, rows}` tables, or an application framed | `add_menu_page` |
| `onPublish { block, author -> }` | after each submit, off the request | `save_post` |
| `every(period) { }` | scheduled jobs, from start to stop | WP-Cron |
| `grant(role, permissions)` | the plugin's permissions, given to roles | `add_cap` |

A layout owes its plugins two calls, `$site.head()` at the end of `<head>` and `$site.foot()` at the end of
`<body>` (see *Themes*). The examples, one artifact each under `kroom-webapp-authoring/plugins/`: **webmaster** (the health of
the site's URLs, one concern seen from three sides: meta and Open Graph tags — a page's own through `$seo` —,
robots.txt and sitemap.xml over `site.pages()`; redirect rules with captures and application resolvers, answered
before routing, and the 404s counted; every page walked on a schedule, every link and picture tried), **forms** (`$forms.contact()` in any block, a honeypot, messages as records
for `forms.read`, daily retention), **analytics** (a cookieless counter's script, authors not counted),
**webhook** (a signed POST on each publish), **mail** (the site's SMTP transport as `site.mailer` — the same
`Mailer` kroom-webapp-auth sends its codes through —, a log of what was sent, a webmail framed in the bar; forms
mails each message to its `notify` address through it). Audience counting stays out of webmaster: it is about
visitors' privacy, not URLs, and its provider is swapped on its own.

### Themes

A page says *what* it is and hands its parts down as closures — Velocity's `#define`, rendered where the
layout places them, straight to the response, nothing buffered — then names its layout:

```velocity
#set($title = "Les Vagabonds")
$seo.description("Un club de go à Marseille")
#define($aside) <article>…</article> #end
#define($content)
  <h1>Les Vagabonds</h1>
  #markdown("description", {"club": $club})
#end
#layout("sidebar")
```

The page's top level only sets things (they are known before the layout writes `<head>`); its regions render
inside the layout. A page without `#layout` is a full document, as before.

A theme is a plugin (`Theme`) naming the layouts it provides. The contract:

- layouts at `themes/<id>/layouts/<name>.html`: `default` required; `article`, `sidebar`, `landing` when it
  has them (a missing one falls back to `default`); private partials under `themes/<id>/inc/`, assets under
  `static/{css,js,img,fonts}/<id>/`; a theme jar keeps them at the classpath root, an application's own theme
  in its templates directory;
- a layout renders `$content`, and `$aside` / `$hero` when defined (`#if($aside)` tests without rendering);
  `$title`; `$site.head()` at the end of `<head>` — the house scripts, the editor's for a logged-in author,
  every plugin's fragments — and `$site.foot()` at the end of `<body>`;
- `$nav`: the menu (`items` of `NavItem(label, href, description, external, children)`), where the request
  stands (`here`, `trail`, `contains(item)`) — the application's (`installContentSite { navigation = … }`),
  or one derived from the pages the site serves; `$theme`: the theme's own settings; `$site.login`.

Several themes may be installed, one active — the admin bar's *themes* panel switches it, live — and an admin
previews another on any page with `?theme=<id>`. A site without a theme wears kroom's `BasicTheme` (pico, the
menu, a footer). `kroom-plugin-dummy-theme` provides every layout of the vocabulary, plainly and unmistakably —
for testing a theme's path end to end.

### The admin bar

`$site.foot()` emits, for whoever holds `site.admin`, a bar on the left of the page — pages (every template
and the pages its blocks say exist), journal, plugins with their settings forms (a secret is never read
back), roles, then the plugins' entries. Its data comes from `/api/site/{pages, plugins, plugins/{id}/settings,
roles}`; its markup is built by `admin.js`. It speaks the editor's language (*The editor's words*); a plugin's
words are the application's to translate, `installContentSite { strings[…] }` keyed by the plugin's ids (`<entry id>`,
`<entry id>.<table id>`, `<plugin>.name`, `<plugin>.description`, `<plugin>.<setting>`, `<plugin>.<setting>.help`,
`<plugin>.<group>`), defaulting to what the plugin says. Pictograms: `kroomAdmin.icons`.

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

# the authoring demo, every example plugin installed, a Mailpit mailbox beside it — admin / admin
KROOM_UID=$(id -u) KROOM_GID=$(id -g) docker compose -f kroom-webapp-authoring/demo/compose.yml up
#   http://localhost:8099/login, Mailpit on :8025 (KROOM_DEMO_PORT, KROOM_MAILPIT_PORT to move them)
./gradlew :kroom-webapp-authoring:demo -Pport=8099   # the same without docker, and without mail
```

## License

Apache 2.0
