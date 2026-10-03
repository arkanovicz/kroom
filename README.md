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
kroom-plugin-*        example plugins (kroom-webapp-authoring/plugins/): webmaster, forms
kroom-webapp-authoring-demo  the authoring stack as a thing you can click (kroom-webapp-authoring/demo/)
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
}
```

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
records for `forms.read`, daily retention). Mail is the site's, not a plugin's: `site.mailer` is SMTP over the
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
#layout("sidebar")
```

The regions are `$header`, `$top`, `$content`, `$east`, `$west`, `$footer`; the layouts `default`, `article`,
`sidebar`, `landing`. `#layout()` takes the site's `layout` setting. `$header`, `$west` and `$footer` have a
site-wide default a page overrides by defining them: the brand, the menu and the way in; the section the
visitor is in, its pages listed; the site's `footer` line. Under the header, a breadcrumb (`$nav.trail`: the
site, the section, the page) shows on any page inside a section — a region partial like the others.

Two kinds of theme, both `Theme : Plugin`, several installed, one active (the `theme` site setting, switched
from the admin bar, live; an admin previews another with `?theme=<id>`):

- a **skin** (`Skin`) names its `stylesheets` (and `scripts`) and renders kroom's own skeleton,
  `kroom/skeleton.html` — every layout in one file, `data-layout` and `layout-<name>` for the CSS, each
  region through a partial the skin may rewrite: `themes/<id>/regions/<region>.html` over
  `kroom/regions/<region>.html`. kroom's `BasicTheme` is one: pico and a few lines of CSS.
- a **markup-owning** theme provides its `layouts` under `themes/<id>/layouts/<name>.html` (`default`
  required, a missing one falls back to it), rendering `$content` and the regions it shows (`#if($east)` tests
  without rendering), `$title`, `$site.name`, `$site.head()` at the end of `<head>` and `$site.foot()` at the
  end of `<body>` — all kroom and its plugins ask.

Both get `$nav` (the menu, see below; `#nav($nav.items)` renders the tree with `aria-current`, a section no
page answers leading to its first page (`$item.link`), each entry's description in a `<small>` for a theme that shows
panels; `$nav.section` is the top-level entry the visitor is under) and `$theme` (the
theme: `id`, `name`, `stylesheets`, `scripts`, `settings`, and any setting by key — `$theme.scheme`). A theme
adds to `<head>` like any plugin, `site.head { }` in its `install`, emitted while it is the active one. Assets
live under `static/{css,js,img,fonts}/<id>/`, private partials under `themes/<id>/inc/`. The authoring tests
hold a `DummyTheme` — every layout of the vocabulary, plainly and unmistakably — for testing a theme's path
end to end; the demo installs it beside the basic one.

### Pages and the menu

The pages are a tree, and the tree is the menu: an entry is a page's segment under its parent, so the tree of
entries *is* the tree of URLs (`company` at the root is `/company`, `history` under it `/company/history`;
`company.html` and `company/index.html` are one page). What exists is the developers' templates — and the
instances of their placeholder pages — plus the pages editors made. An editor's stored arrangement only orders
and words them (`MenuItem`: `slug`, `label` and `description` by language, `children`); it never makes a page
exist: a page made since appears after the arranged ones, an entry no page answers any more is dropped. Until
one is stored, the tree is derived from the pages, a deeper URL nesting under its first segment.

A page's label is its title, its description the page's — one field each, by language, so the menu and the page
cannot disagree: the site's `lang` is the default, `languages` the others it speaks, and a request's language
is what `installContentSite { requestLanguage = { it.language } }` answers (kroom-webapp-l10n's, or the
application's), the default filling what a language lacks.

**Authored pages.** A page need not be a template: an editor makes one, a record (`path`, `layout`,
`draft`|`published`), and kroom renders it through one template, `kroom/page.html`, each region a block in the
content store right where a template's would be (`pages/company/history/content.md`), edited in place like any
other, previewed within its page. Authored pages answer after the templates, in the not-found phase: a
developer's page wins, an editor cannot take `/login`. Deleting the record leaves its blocks in the store.

A region is what its block makes it. Written, everyone sees it. Not written, a visitor sees nothing there —
or the site's default, for `$header`, `$west` and `$footer` — and whoever may write it finds a sliver in its
place, unseen until hovered, that says what it is (*right sidebar*) and offers the edit handle; the default
itself carries the handle where there is one, and writing it overrides the default for that page. Trashing
the block (the editor's trash, `DELETE /api/content/{path}`) brings the default, or the emptiness, back.

A page is a `draft` until published: its editors see it, marked *draft* in the menu; visitors and the sitemap
do not.

**Moving.** An authored page with no page under it moves by being dragged under another parent — its record,
its blocks with their history (`ResourceStore.moveAll`), its words. The move is refused when a page (or a
placeholder template) already answers the destination, when the page has pages under it, or when someone else
is writing one of its blocks. A developer's page is reordered among its siblings, not moved: its address is
its template's. No redirect is written from the old address; the not-found table of the *site* entry shows
who still asks for it.

**Rendering.** Three site settings shape the menu a visitor gets: `menuDepth` (how deep the header's menu goes,
2 by default), `menuPanels` (the header shows a section's pages as a hover panel) and `west` (the section's
pages on the left by default, or nothing). A section no page answers leads to its first page.

**The *pages* entry** of the admin bar is that tree, compact, one line per page — a handle to drag it by
(SortableJS; a phantom row shows where it lands, a page's own list, empty, is how it gets a first child;
a developer's handle is greyed) and its label, which opens an accordion: the label and description in the
chosen language, the page itself (a section offers to create it), and for an editor's page its layout,
publish/unpublish, delete. A new page is typed by its name, the segment derived from it (`Notre Société` →
`notre-societe`) until typed over. Every gesture stores the tree.

`GET`/`PUT`/`DELETE /api/site/menu` (the tree; its arrangement; forgetting it), `POST /api/site/pages`
(`{path, label, layout}`, a draft), `POST /api/site/pages/move` (`{from, to}`),
`PUT`/`DELETE /api/site/pages/{path}` (layout, status), all for `pages.edit` — the editor's; an `author` writes
blocks and nothing else. An application computing its own menu sets `navigation = { call -> List<NavItem> }` over all this.

### The admin bar

`$site.foot()` emits, for whoever may open at least one of its entries (*pages* asks `pages.edit`, the others
`site.admin`), a bar on the left of the page — the site's own settings (below), pages (the tree, see *Pages and
the menu*), journal, media, plugins with their settings forms (a secret is never read back), themes (when more
than one is installed), roles, then the plugins' entries. Its data comes from
`/api/site/{settings, menu, pages, plugins, plugins/{id}/settings, themes, roles}`; its markup is built by `admin.js`. It speaks the editor's language (*The editor's words*); a plugin's
words are the application's to translate, `installContentSite { strings[…] }` keyed by the plugin's ids (`<entry id>`,
`<entry id>.<table id>`, `<plugin>.name`, `<plugin>.description`, `<plugin>.<setting>`, `<plugin>.<setting>.help`,
`<plugin>.<group>`), defaulting to what the plugin says. Pictograms: `kroomAdmin.icons`.

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

## Starting a site

From a machine with Docker and nothing else:

```
sh -c "$(curl -fsSL https://republicate.com/kroom/create.sh)"
```

The starter asks for a name, a folder, the languages, a contact email (given, the contact form is on and its
messages go there) and a port, then writes the site: a Kotlin build pinned to the kroom it came from (and to
its velocity engine, both published on `republicate.com/maven2`), one `Main.kt` around `installContentSite` —
a `FileStorage` under `data/`, the basic theme, the webmaster plugin, an admin whose password is in `.env` —,
two pages, a `Dockerfile` and a `compose.yml` running the installed distribution with `./data` bound, and a
README. `docker compose up` builds and serves it. The starter is `kroom-webapp-authoring-starter`, a jar of
the standard library and the templatized files under `starter/`; `create.sh` fetches the latest and runs it in
a bare JRE container, as you, in the current directory. `publish-snapshot.sh` publishes both.

## Run Examples

```bash
./gradlew :kroom-server:run           # SSE playground at :8080/playground
./gradlew :kroom-examples:chifoumi:run  # Rock-paper-scissors at :8081

# the authoring demo, every example plugin installed, a Mailpit mailbox beside it to read what the site sends — admin / admin
KROOM_UID=$(id -u) KROOM_GID=$(id -g) docker compose -f kroom-webapp-authoring/demo/compose.yml up
#   http://localhost:8099/login, Mailpit on :8025 (KROOM_DEMO_PORT, KROOM_MAILPIT_PORT to move them)
./gradlew :kroom-webapp-authoring-demo:run -Pport=8099                # the same without docker, and without mail
```

## License

Apache 2.0
