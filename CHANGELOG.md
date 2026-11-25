# Changelog

All notable changes to kroom will be documented in this file.

## [Unreleased]

### Changed (breaking)

#### kroom-server — the process's index of rooms is `Rooms`
- `object Lobby` is renamed `Rooms` (`Rooms.getOrCreate`, `register`, `get`, `remove`, `listRooms`, …), its logger
  `kroom.rooms`: a rename and nothing else. The word `Lobby` is free for the room of rooms to come.

#### kroom-examples/chifoumi — `ChifoumiState`, `ChifoumiEvents`
- Chifoumi's `LobbyState` and `LobbyEvents` take the app's name, in the code, the pages' headers and the guide's
  examples: those two names are kroom's from here on.

#### kroom-server — a logic hears every connection, each after its graces
- `LogicRoom` no longer keeps a `join` or a `leave` that is not a user's first or last from its logic: a handler
  declared on `events.join` / `events.leave` hears every connection, and reads `first` / `last` when the user is
  what it means. What it emits is framed whether the `join` / `leave` itself is on the wire or not.
- A connection gone beside another of its user (`leave`, `last` false) is heard once `leaveGrace + lostGrace` are
  over, where it was heard at the drop; the same tab back within them is not heard at all. Nothing changes on the
  wire: the user's `join`, `leave`, `status` and composed status are as before. `Table.onPlayerConnectionClosed`
  is told that much later. Pinned in `RoomTest`, `LogicRoomTest`.

#### kroom-webapp-authoring — every entry of the menu is a page
- The tree is completed at start (`AuthoredPages.complete()`, on `ApplicationStarted`): a path with pages under
  it and none of its own — `/legal` over `pages/legal/terms.html`, `/club` over the placeholder
  `pages/club/_club_.html`, before any instance — gets an authored page like any other: `wide`, signed `kroom`
  (`AuthoredPage.COMPLETER`), its content block its name as a heading and the list of the pages under it
  (`AuthoredPage.TOC`, a loop over the `$children` and `$title` `kroom/page.html` hands the block). It is out when
  visitors already reach something under it, or when nothing is there yet; a draft when all under it is drafts.
  It only fills gaps: a page or a block an editor rewrote is theirs.
- A parameterized address is left alone: `/club/13Ma` when only `pages/club/_code_/members.html` exists is no
  page, answers 404 as before, and is in nobody's menu, nor what is under it (`MenuItem.view` drops what no page
  answers; `GET /api/site/menu` skips it).
- The API keeps the tree whole: `POST /api/site/pages` answers 409 `noParent` under a path with no page (a
  top-level page needs none), `POST /api/site/pages/move` refuses such a destination; `DELETE
  /api/site/pages/{path}` answers 409 `pageHolds` while pages live under it (an editor's page, a visitor's URL, a
  template's route), and `PUT` refuses to unpublish it (409 `pageHolds`) while visitors reach one of them.
- Publishing climbs: a page published (`PUT`, or `POST` with `"status":"published"`) publishes the drafts above it.
- A page is made where a developer's placeholder answers (`POST /api/site/pages` with `/club/9Zz` under
  `pages/club/_club_.html` answered 409 for any name: nothing could be made under such a folder): it is the
  template's, laid out by it and written in its blocks, its record saying it is there and whether it is out —
  kind `instance` with a `status`, no layout to choose, not movable, deleted with its blocks.
- `installContentSite { hiddenPages += "pages/player/_id_.html" }`: a template served and out of the tree — in
  no menu, no sitemap, nothing made above it (a folder of pages a database lists got an empty page, back at each
  start). The login page was the only one.
- Pages panel: the page one is on is told apart in the tree (`kroom-admin-here`, `aria-current`), open or not;
  another page's layout changed reloads nothing (it did, each time) — only the layout of the page one is on.
- Basic theme: the left column is capped at 12rem (it took a quarter of the row, far too wide on a large screen).
- The breadcrumb shows on a top-level page too (the site, then the page); the home page has none.
- A page in no menu has a trail (`Navigation.trail`): the nearest page above it that is in the menu, then itself,
  named by its segment — a record page showed no section menu and a breadcrumb of the site alone.
- An authored page's record reads back who made it and when (`author`, `created`, `published` were written and
  read as unknown, or now).
- Gone with the page-less entry: `NavItem.link` and `NavItem.resolved` (a theme links `$item.href`), the kind
  `section` (`Site.kinds()` answers `template`, `instance` or `authored`), the menu API's `resolved`, the pages
  panel's "no page here — create this page", the words-only branch of `#nav` and of the breadcrumb.
- Pinned in `PagesTest`, `NavigationTest`, `PluginTest`, `admin.mjs`.

#### kroom-webapp-authoring — three layouts: `article`, `wide`, `gallery`
- The vocabulary was `default`, `article`, `sidebar`, `landing`, `gallery`; a layout now says only what no region
  can — how wide the content runs, and whether the section's menu stands beside it. `article`: a reading column
  (70ch in the basic theme), the section's west menu on its left; what a new page gets, the default of the site's
  `layout` setting, the layout every theme provides and the fallback for one it lacks (`FALLBACK_LAYOUT`).
  `wide`: the whole width, no west. `gallery`: the west menu and all the width beside it, seeded with a gallery
  at birth by the gallery plugin.
- Basic theme: on a small screen the west menu is one line that scrolls sideways (it stacked above the content,
  a screen of links before the page): where else to go, the current page left to the breadcrumb and its own
  pages keeping its place in the line; the breadcrumb sits closer under the header.
- To its editor, a block that renders nothing keeps a place (it was a line nobody could find); with the admin
  bar's eye released each block wears its name above its top left corner, and an unwritten side block's
  placeholder fits its words (they ran out of the page). With the eye pressed — the page as visitors see it —
  a region nobody wrote takes no room (an unwritten right block cost a column's gap, an unwritten top a band);
  whoever writes without a bar has no eye and keeps the strip to hover. An article is centred when nothing
  written stands beside it.
- `sidebar` and `landing` are gone: a side column is an `$east` written, a page opening on a band a `$top`, on
  any layout. A stored page whose layout is no longer named (`default`, `sidebar`, `landing`) reads as `article`;
  `POST`/`PUT /api/site/pages` answer 400 for one.
- A markup-owning theme's required file is `themes/<id>/layouts/article.html` (was `default.html`).
- A page an editor creates is born empty, a root page too (it was born with the loop listing its pages): the
  section's menu is that list already, and an editor opening an empty block found code. The loop
  (`AuthoredPage.TOC`, over the `$children` `kroom/page.html` hands the block) stays for the pages kroom makes to
  complete the tree.
- Pinned in `ThemeTest`, `DummyThemeTest`, `PagesTest`.

#### kroom-webapp-velocity, kroom-markdown, kroom-webapp-authoring — a value is data, markup is a `SafeText`
- `installVelocity` names velocity's `DynamicEscaper` (`event_handler.reference_insertion.class`): in a web page
  (`.html`, or an `##@output web` header) every value a reference inserts is escaped for where it lands — text, an
  attribute, a script, a style; `<title>` is text up to its closing tag. Markup passes as
  `org.apache.velocity.engine.SafeText`, and only as that: a tool that answers html says so by its type, and
  escapes itself what it holds that someone typed. An application's own handler, or none (`""`), goes in
  `properties`. Other outputs (`.txt`, a mail's body) are left as written. Pinned in `EscapingTest`.
- It closes a hole: a page's label and the site's name went raw into `<title>`, the brand and the menu of every
  page — whoever could name a page ran script in every visitor's browser. Pinned in `PagesTest`.
- `#markdown` hands its wrapper `$html` as a `SafeText`; `$site.head()` and `$site.foot()` answer one;
  `$gallery.show(…)` and `$forms.contact(…)` too. A region a page `#define`s is written where the layout calls it,
  so what it inserts is escaped there. `Site.head { }` / `foot { }` fragments stay strings: markup by contract.
- A menu link's address is `http(s):`, `mailto:` or `tel:` — any scheme went (`javascript:`), `PUT /api/site/menu`
  answers 400 for another.

#### kroom-markdown — a block keeps its html and loses its scripting
- What a block renders is sanitized (jsoup 1.24.1, an allowlist over flexmark's output): html stays, scripting
  goes, whoever typed it and wherever it came from — no `script`, `style`, `iframe`, `object`, form control; no
  `on*`, `style` or `data-*` attribute (the page's scripts read those); an address is `http(s):`, `mailto:`, `tel:`
  or relative; the `kroom-` classes and ids are the house's. What markdown renders passes (tables and their
  alignment, task lists, code and its language, struck text). The html comes out as jsoup writes it (`<br>`).
- The one markup that passes as written is a tool's, answered as a `SafeText`: the block engine's insertion
  handler sets it aside while the block is merged (an empty `div` or `span` named by a nonce of the render stands
  for it, a block element being an html block of its own), and it is put back once the rest is sanitized. So
  `$gallery.show(…)` and `$forms.contact(…)` no longer shape their html for markdown (one line, no blank line, a
  closing newline). A tool that answers html as a plain string is sanitized like anything else: answer a `SafeText`.
- A value a reference inserts is data, shown as typed: a club named `<b>Lyon</b> *Go*` shows those characters,
  in a heading, a table cell (its `|` is no border), a code span, an attribute, a link's text. A word only the
  render can name stands for it in the markdown, and the value is written where the word landed once the block
  is html — a text, an attribute's value — before the allowlist, so an address a value makes is judged like any
  other. A value no longer carries markdown or html: a snippet to reuse is a `%define`, not a `%set` string.
  Read into a string (`$tool.find("n$count")`, `%set($x = "$a b")`, a test), a reference is the value itself:
  the block is merged into a sink the engine knows, and only what is written to it is set aside.
- A body standing in for a block nobody wrote (`#@markdown("top") … #end`) is the page's own text, written
  where it lands (velocity's `BodyContent`): what it inserts is escaped there — it went raw.
- Pinned in `SanitizerTest`, and through a page in `PagesTest`.

#### velocity 3.0.0-BETA-20261116-04
- Bumped (`-03` came without a note: a macro defined twice, in one template or by a parent and the template it
  parses, keeps its first definition unless `replace_global`). A map or a list inserted where a literal goes — a
  script's code, an `on*` handler, a `.json` template's value — is written as a literal of its structure, every
  string in it escaped for the position; it was its quoted `toString()`. A string stays a string: a json text
  held as a `String` still arrives quoted. No test moved.

#### kroom-webapp-l10n — `$jsTranslations` is the catalog, not its text
- `$jsTranslations` answers the request language's catalog itself (a kson object) where it answered its json
  text: in a page's script `window.i18n = $jsTranslations;` is an object, nothing to parse and nothing a
  translation holds can end the script (under the escaper `installVelocity` names, the text arrived as a quoted
  string). In an attribute or in text it is still the json text, escaped there. **Breaking** for a typed request
  scope: `KroomL10nRequestScope.jsTranslations` is a `Map<String, Any?>`. Pinned in `JsTranslationsTest`.

#### velocity 3.0.0-BETA-20261116-02
- Bumped. One `#parse`, its target literal or computed: the interpreter no longer splices a literal one into its
  parent's tree, the child is resolved at render either way. So a page translated at run time (`Translator`) no
  longer translates the fragments it `#parse`s — it did for a literal target only, the spliced text being part of
  the page's tree. A fragment to translate is included by `#translate`, its name written or computed
  (`TranslateEscapingTest`); `#parse` includes it as written, in every language. The rest of a `#parse` /
  `#include` line is gobbled as 2.5 does. No test moved.

#### velocity 3.0.0-BETA-20261116-01
- Bumped. What a classic directive renders goes to the merge's own output, so a template included by `#translate`
  is escaped as one included by `#parse`: its references went raw, in the source language as in a translated one
  — a title or a name someone typed reached the page as markup. Pinned in `TranslateEscapingTest`. A `Parse`
  subclass's child is now rendered as `#parse` renders one: the include handler is asked under the name
  `translate`, a bare `#break` in the fragment stops the fragment only, `$template` stays bound, and past
  `directive.parse.max_depth` it throws `ParseErrorException`. A browser engine no longer warns that an
  `##@output web` region is inert when its sink takes values raw (`HtmlDomSink`).

#### velocity 3.0.0-BETA-20261113-01
- Bumped. A module's compiled templates may live under a prefix (`resource.loader.stock.prefix`): `en/lines.html`
  of a module prefixed `arkad` is the template `arkad/en/lines.html`, its registry
  `velocity.templates.arkad.VelocityTemplates`, so several modules' pages share one engine
  (`StockResourceLoader(a::resolve, b::resolve)` in a browser) and `#parse` each other's. Two modules' macros of
  one name are refused. Nothing here takes a prefix yet; no test moved.

#### velocity 3.0.0-BETA-20261112-01
- Bumped. `HtmlDomSink` leaves in place the items that did not change order when one before them is gone: it
  moved every survivor after it, and a node moved loses the focus — a textarea being typed in, under an item
  removed, was blurred (seen from a text written by several: a writer below a paragraph that went gave its own
  back). No test moved.

#### velocity 3.0.0-BETA-20261110-03
- Bumped. A fault during a merge into `HtmlDomSink` reaches the caller as thrown (it was lost to "unclosed
  elements", and every later merge failed); the merge after it empties the root and builds the page again,
  asking `clean` about nothing; `HtmlDomSink.reset()` does the same on demand. A `#break` or `#stop` inside an
  element closes what it left open. Blocks must be balanced: an element closes in the block, or the iteration,
  that opens it — refused at the end of the pass otherwise. `Tree.clear(element)` is new: chifoumi's
  `TreeDouble` has it. chifoumi's regions are balanced, no test moved.

#### velocity 3.0.0-BETA-20261110-01
- Bumped. Strict mode and the invalid-reference handler as in 2.x, with `event_handler.invalid_references.missing`
  to offer a handler what strict mode faults on (not used yet); under strict mode a key a Map does not hold is a
  null value. No test moved.

#### kroom-common, kroom-server — presence LOST
- `Presence.LOST`, last in the order: a user with no client left. A room with a `lostGrace` (new `protected open val`,
  0 = as before) says it past `leaveGrace` — a room-born `status` frame (the gone tab as its client), `users` keeps the
  user as `LOST` and counted — and `leave`s after `lostGrace`; a connection back meanwhile is a `status` (ONLINE), no
  `join`. A client posting `LOST` is refused (`invalid`: only the room says it). A drop's delayed steps are stale once
  another drop follows (drop, reconnect, drop inside a grace: the grace counts from the second — the `leaveGrace` path
  had the race too). The `leave` frame's user reads `LOST` where it read `AWAY` (chifoumi's golden refreshed).
  `leaveGrace` 0 with a `lostGrace` says `LOST` at the drop itself (it skipped LOST, straight to `leave`).

#### kroom-client — a fault is its region's alone
- `Room.merge` catches per region: a region whose merge faults no longer stops the ones after it. Its root
  carries `data-kroom-broken` (`Room.BROKEN`) until its next good merge — the page's stylesheet says what a broken
  region looks like — and `Room.onFault(template, fault)` is told (it logs by default). The region waits for a
  root it reads to change, like any other, and is then built again whole: the sink starts over after a fault
  (velocity 20261110-03). Pinned in `RegionsTest`: the page's words failing `match.html` leave `round.html` merged.

#### kroom-common, kroom-client — targeted merges
- A view knows its `owner` (the view it was read from) and its `identity` (`id`, else `seq`, when declared and present);
  a write through a field, `Keyed.add/remove` or `Log +=` reports every identity on its path to the root's
  `State.dirty`, when a browser's Room keeps one (null on the server: nothing recorded).
- `Room.frame` merges targeted: `HtmlDomSink(tree, root, ::key, ::clean)` — an item whose key is in no dirty set
  (`state`'s, `page`'s) is clean, moved into place, its body not rendered. `key` answers a `Position(index)` for an
  item with no identity (never clean: a removal before it shifts it). `merge(all = true)`, the default and what a
  timer calls, renders every item; a snapshot too. Pinned in `RegionsTest`: a frame writing one line of the chat
  renders one body (the tree double counts the sites read); every region's tree still equals its text render after
  every frame.
- A region is merged when a root its `##@` header declares changed (`Template.header`, velocity -02): `State.touched`
  (a write through the fields since the last merge) for `state` and `page`, a snapshot for `me`; a header that
  declares nothing merges on every frame, a region never merged is. The header declares every root the region reads,
  a page getter over the state included — `round.html` now declares `state`. `Keyed.remove` of an absent id marks
  nothing. Pinned in `RegionsTest`: a frame the state and page have nothing for merges no region; a `leave` the state
  has nothing for merges `lobby.html` (its feed) and not `online.html`.

#### kroom-common, kroom-client — a form's text in its kind
- `Field.parse(text)`: a form's text in the field's declared kind — a number parsed, a checkbox's `on` true, `""` null for
  every kind but STRING (the key stays: a field emptied on purpose, a vote undone), a text no kind reads left as is for
  validation to refuse. `Intents` applies it to every `data-kroom-<field>` and form value by the intent's declaration
  before the view's check and the POST: `replyTo: "42"` no longer goes on the wire as a string, an emptied `Long?`
  field is null rather than `invalid`. An undeclared key stays a string (the shape refuses it). `Intents(root, events, act)`.
  Pinned in kroom-client's `IntentsTest` (jsTest, under karma like kroom-common's: the browser test task is on again).
- `Intents` tells a trigger by its selector (its intent and its `data-kroom-*` attributes, `-of` included), no longer by
  its intent's name: a vote on line 7 posts while line 3's is in flight (it was swallowed), a second click on the same
  trigger still is; each is busy, settled and shows its reason on its own, found again by selector after a merge rebuilt
  it. `IntentsTest` runs under karma only (no `document` on node).

#### kroom-server — the history between two bounds, the store's parameters
- `ChatHistory.between(after, before, n, params)`: the history route's one call, up to `n` lines oldest first — strictly
  between the two seqs when both are given (`?after=A&before=B`, a 400 `before_and_after` until now; by default
  `after(A, n)` cut at `B`), else `after` or `before` as before. `params` = the request's query parameters kroom does not
  read (`after`, `before`, `n` removed), for a store with more to say (a span, a ranking); the default ignores them, a
  store that overrides nothing serves as before. `ChatRoom.between` delegates. Pinned in `RouteTest`.

#### kroom-common, kroom-server — the avatar in presence
- `UserPresence.avatar` (an image URL, as of the join: a snapshot's `users` and `me`, a `join`'s `user`), the server's from
  `User.avatar`; a user without one has no key. A picture changed meanwhile shows at the next join or snapshot.

#### kroom-client — the stream follows the page
- `Room.connect()` returns a `Stream` (`close()`), no longer the bare `EventSource`; `kroom.connect()` unchanged. The
  stream closes on `pagehide` and opens again on `pageshow`, the same URL and tab id, the snapshot first like any
  reconnect: a page parked in the back/forward cache kept its `EventSource` open, and the server heard the drop only
  when the browser discarded the page (60 s in Chrome, arkad 2026-11-08) — the user listed present in a room they had
  left, the `leave` and what it persists that late. Back within the graces is a `status`, no `leave`, no `join`; a
  reload's `pagehide` is the close the unload made anyway. Nothing reconnects while parked (a closed `EventSource` has
  no retry); a `Stream` the page closed stays closed. `report()` unchanged: `AWAY` remains `visibilitychange`'s, a live
  hidden tab keeps its stream. Pinned in `StreamTest` (karma).

#### kroom-client — the tab reports its presence
- `Room.report(idleAfter = 120_000)` (js; `kroom.report()` on the facade): posts kroom's `status` through `act` on change
  only — the tab hidden → `AWAY`, no input (mouse, key, touch, scroll) for `idleAfter` ms → `IDLE`, back → `ONLINE` —
  and again after each snapshot when not `ONLINE` (a reconnect is a join, and the room starts a joiner `ONLINE`); a
  spectator posts nothing, a refusal is one console line. Returns what stops it. Pinned in `PresenceTest` (karma).
- A page going away (`pagehide`) posts nothing until `pageshow`: a reload fires `pagehide`, then the hidden
  `visibilitychange`, and that `AWAY` landed on the reloaded page's seat (same tab id, `api.js`), which had started
  `ONLINE` and so said nothing more — a visible tab listed `AWAY` (arkad, 2026-11-06). `UserPresence.clients` documented
  as the fold keeps it: as of the user's first `join`, `status` alone following.
  `status.js` (`StatusTracker`, lowercase statuses and an `OFFLINE`, posting to `game/{id}/status`) predates the events
  and is the legacy chifoumi page's alone.

#### kroom-webapp-core
- An unhandled exception answers 500 with a fixed "Internal Server Error": its message (paths, template names,
  values someone typed) goes to the log only. Pinned in `InternalErrorTest`.
- `StaticConfig.devDirs: List<File>`, tried in order before the classpath (sources, then a build output that cannot live in
  them: a Kotlin/JS bundle); `devDir` stays as the one-directory shorthand.

#### kroom-examples/chifoumi
- The page memories keep fields, like any State: `LobbyPage.feed` is a `log(::Line, max = 10)` read newest first,
  `GamePage`'s `last`, `reveal` (a `Reveal` view) and `note` are `obj` / `string` fields — a merge sees what went
  through them.

#### kroom-common
- `ChatState.last`: the seq of the last line said, bumped by `reduce`, seeded by the room from the store; `ChatLogic`
  numbers `last + 1` instead of the tail's last seq, so a tail may be empty (`max = 0`) or in another order.
- An event's checks compose like `init` blocks: a subclass's `check {}` runs after its parent's, the first refusal
  wins (a `Line : Chat` keeps Chat's "blank"). `Event.checkState` → `checkStates` (parent's first); a room verifies
  each at construction.

#### kroom-webapp-oauth
- `onAuthenticated` gets the provider's claims as a third argument, `(session, call, claims) -> UserSession?`: the
  validated id_token's claims (OIDC), the userinfo document (OAuth2; an extractor's own `Profile.claims` win).
  `Profile.claims` carries them. For an avatar (`picture`), groups mapped onto members (`groups`).

#### kroom-server
- `ChatHistory.last()`: the seq of the last line stored (default: the tail's), read once at `ChatRoom` start.
- `ChatHistory.after(seq, n)`: up to n lines newer than seq, oldest first (`MemoryChatHistory` has it); a store
  implements it. `ChatRoom.after`; the history route takes `?after=` (with `?before=`, a 400).

#### kroom-server — one ordered writer, the store's stage
- `Room.persist(event)` (suspend, off the loop): every frame, a copy taken on the loop, in the room's order; the event
  goes `PERSISTED` after it, a stopping room drains it. `ChatRoom`'s private writer is gone into it: lines go to
  `ChatHistory.append`, every other frame to `ChatHistory.persist(event)` (new, nothing by default), so a reaction
  never reaches the store before its line. Every event now reaches `PERSISTED` (with no store, at once): a verdict is
  "not `REJECTED`". `Room.stopped()` (added in this snapshot) is gone: the room closes its writer itself. The copy
  carries the metadata (seq, from, at).
- A `LogicRoom` handles every entry of its catalog, `event(::E)` ones too: a client still cannot post them (no route,
  `unknown` at the door), server code `act`s them — a store's answer, as the request's author, decided by the logic
  like an intent. A handler's emission carries its intent's author (`meta.from`, `tab`): a `USERS` emission reaches
  the author's tabs without `.to()`, a store knows whose request it answers.

#### kroom-server — rooms on demand
- `Route.rooms(build, principal)` serves every room of a kind, `/rooms/{room}/…` (stream, intents, catalog,
  scroll-back), through `Lobby.getOrCreate`; a room closing under a call is resolved once more. `Route.intents`,
  `Route.events()` and `Route.chatHistory` are gone into it.
- `Room.role(user): Role?` (`ACTOR`, `SPECTATOR`, null) decides every route call, on the loop: the stream (an
  anonymous caller may spectate), each intent (actors only, asked in the step that applies it: a tab joined as a
  spectator acts once its user's role allows), the scroll-back and the catalog. Null is `401 unauthenticated`
  anonymous, `403 forbidden` otherwise. Defaults to today's: signed-in callers act, anonymous ones are refused —
  the catalog included, which was open.
- Intent replies: `403` for `spectator` and `forbidden` (a missing permission too, which was a `400`).
- A refused stream is a status before it starts (the 401 used to be attempted after the SSE 200).
- What was queued behind a `stop()` is answered `closed` instead of waiting forever.
- A room closes once idle: empty for `idleTimeout` (30 s, counted from its last leave; a timer finding the room
  busied since does nothing), not at its last leave; a room built and never joined closes too. Only a room built
  on demand closes (`shouldCloseWhenEmpty()` defaults to that): a room the host built lives with the app.
- `Lobby.getOrCreate(id, build: suspend (id) -> Room?)` is suspend and double-checked: the running room, else under
  a lock looked up again, else built (null: no such room); what it builds closes once idle, is forgotten
  (`Lobby.forget`, only that instance), and the next call builds it anew. `Room.running`.

#### kroom-common, kroom-server — one chat line, the app's
- `Chat` is open and every chat type carries the line class: `ChatState<C>(json, max, line)`, `ChatEvents<C>(line)`,
  `ChatLogic<C>(clock, events)`, `ChatHistory<C>`, `MemoryChatHistory<C>`, `ChatRoom<C, S>`; `ChatState()`,
  `ChatEvents()`, `ChatLogic()` build plain `Chat` lines. An app's subclass fields travel in the frame, the tail, the
  snapshot, the store and the scroll-back. The room hands the store, and seeds the tail with, deep copies (taken on
  the loop). `sendChat` answers the line.
- `Author` is common, a view over `{id, name, avatar}` (`Chat.author` reads it typed); the server's `Author` data
  class and `ChatMessage` are gone, and so is a line's `from` (it repeated `author.name`). Chifoumi's golden
  refreshed.
- `GET …/history` serves each line as the reader's `view`, as the snapshot does.

#### kroom-common — events v4, the State side (step 5)
- Field kind `keyed(::V)`: views by their `id`, in insertion order, on the wire an object keyed by id;
  `add` on a present id updates in place, `remove(id)`, `id in`, `[id]`, iterated as values (a page's
  `#foreach`, `.size`). `list<T>()` and `map(::V)` are gone. Chifoumi: `queue` and `MatchView.played` are
  `keyed(::Player)` (`{id, name}`: the queue carries names, the logic's name table is gone), `matches` is
  `keyed(::MatchView)`, `match` frames carry `player: {id, name}`.
- Presence is a field: `State.users` is `keyed(::UserPresence)` on the base class, and `State.reduce` (now
  `open`, a room's State calls `super` first) applies `join` / `leave` / `status`; `Store` folds through it
  alone and loses `users`. The server keeps `users` by folding the same frames, so a snapshot is the State
  at its position: a joiner is not in its own snapshot, its `join` follows (it used to be added from
  membership). `reduce`'s parameter is `event`.
- Selective fields: `.visibleTo { viewer -> … }` (a rule over the viewer's user id, read on the instance)
  replaces `.mine`, `.hidden` = `visibleTo { false }` (the server's alone); `view(viewer)` drops what the rule refuses at any depth (`obj`, `keyed`, `log`), and
  a frame carrying a selective field is serialized once per user (once for all otherwise); a snapshot is
  `view` of its viewer's user. `describe()` says `selective`. A setter given null removes the key.
  Chifoumi: the rule holds no state (`Game` gone): a round's moves are `MatchView.move1/move2`, each its
  player's, written by the fold of `play` (`Play.move` visible to its player); `GamePage.move` reads the
  marking, so a reconnect shows the move played.
- The logic without tokens: `Logic.decide(state, actor, intent)` answers a `Reason` or an `Emit`
  (`emit(…)`, `done`); `Fired`, `fired`, `held()`, `releasing`, `Logic.cancel` and the room's held map
  are gone. An event declares what it `emits` (was `fires`, `Fires` → `Emits`) and whether it is
  `cancellable` (was `pending`): its deposit is the State's, `cancel` reaches `decide` like any intent
  and reads the State (`not_cancellable` from the room otherwise). The snapshot loses `pending`, the
  reply `{pending: true}`, `Meta.held`. `Event.emit(seq)` / `emitted` → `sequence(seq)` / `sequenced`.
  `ChatLogic` moves to kroom-common, pure: a line's seq follows the State's last, the clock comes
  through its constructor. Chifoumi: `ChifoumiLogic` is common (`chifoumi-common`, its clock and match
  ids handed in), `cancelMatch` / `cancelPlay` typed members, `in_queue` / `nothing_to_cancel`; its pure
  tests are `ChifoumiLogicTest` (JVM + node); `LobbyTest` records with fixed ids and clock, so the
  golden is the same at every run.
- `check` on events, common: declared in the event's body typed on the State it reads (`init { check
  { state: LobbyState, actor -> … } }`), `checkState` the class; a `LogicRoom` refuses at construction
  an event checked on another State, runs the check first on the loop, then `decide` (which need not
  test again). In the browser, kroom-client's `Room.check(type, payload, client)` runs the declared
  shape then the check on the viewer's view (`me` as the actor, null for a spectator), and `act`
  rejects without a POST, shaped like the reply (`data.reason`). `Chat` refuses a `blank` line.
  Chifoumi: `in_match` / `in_queue` on `Match`, `not_in_match` / `already_played` on `Play`.
- The declaration, no generator (step 6, 2026-10-31): `RoomEvents` replaces `Registry` — a room's
  alphabet as an object of typed entries (`object LobbyEvents : ChatEvents() { val play = intent(::Play,
  cancellable = true); val round = event(::Round) }`), kroom's `join` / `leave` / `status` / `cancel`
  inherited, `register` kept for the `on()` path; `Emits` and the event's `emits` / `cancellable` are
  gone (`cancellable` is the entry's, an emitted name must be one of the room's). `Logic` is an abstract
  class over its events: `on(entry) { state, actor, event -> … }` (typed, no `when`, the one cast
  kroom's), `cancel(entry) { … }`, coverage checked when the room is built (`checkCoverage`) or at the
  first decision. `ChatEvents` composes; `Room.events`, `Store(state, events)`, kroom-client's
  `Room(name, state, events, engine)`. Chifoumi: `LobbyLogic` and the grammar comment gone,
  `LobbyEvents` + `ChifoumiLogic`'s handlers.
- Routes from the room's events (kroom-server): `Route.room(room) { call -> user }` mounts the stream
  (`GET /rooms/{id}/stream`), one `POST /rooms/{id}/{name}` per intent, the catalog and, for a chat, the
  history, installing ktor's SSE when absent; `Route.intents` is now `POST /rooms/{room}/{name}` too (the
  payload is the body, no `type`), `/act` is gone. kroom-webapp-assets: `api.act(room, type, payload)` posts to
  `rooms/<room>/<type>` (an app's own path keeps `{type, …}`), `api.base`. kroom-client: `connect()` defaults to
  `<api>/rooms/<name>/stream`, `act(event)` typed, `on(entry) { event -> }` typed beside `react`. Chifoumi:
  its stream block, the three principals and `Lobby.register` give way to one `room(ChifoumiLobby) { … }`;
  the lobby's chat scroll on `on(LobbyEvents.chat)`. `RouteTest` pins the routes.
- `Route.room`'s stream: a frame written to a closed tab ends the stream with one log line, no stack trace
  (`ClosedByteChannelException` caught); the room's `leave` runs. A heartbeat written to one still logs ktor's
  trace: its `heartbeat {}` launches the writes unguarded (to be reported upstream).

#### kroom-server — rooms over Event (branch `kroom-events`)
- `handleAction` / `ActionResult` are gone: a room declares each event once, class and handler
  together, `on(::Play) { actor, play -> Reason? }` (null = applied); the class goes in the room's
  `catalog` (`GET /rooms/{room}/events` serves it as data, `Route.events()`), the handler runs on
  the loop. The door (`act(user, tab, name, json)`, `Route.intents`) refuses an unknown name, a bad
  shape, a missing permission (`permits(user, permission)`, open) or a spectator with `400` before
  the loop. `act` returns `Deferred<Event>`, the event in its final stage.
- The feedback is the event itself: `accepted` / `rejected` frames are gone, the acting event comes
  back to its emitter with `_.stage` (`APPLIED` / `REJECTED`, `_.reason` + `_.args`) after the
  handler's own emissions; a rejection reaches the emitter alone. Every frame's `id` is its seq.
- Audience is declared on the class (`ROOM` / `USERS`; `GLOBAL` is gone, nothing crosses rooms), members named at `emit(event, to)`,
  the emitter when none. `broadcastToActors` / `broadcastToSpectators` / `sendTo(actor, …)` are
  gone; `broadcast` and `sendToUser` birth room-born `Generic` events.
- Hooks are gone: `Join` and `Leave` are events the room births (`user`, `displayName`, `spectator`,
  `first` / `last`), handled with `on(::Join)` / `on(::Leave)`; `Table` seats there, its
  `onPlayerSeated` / `onPlayerDisconnected` / `onPlayerConnectionClosed` are no longer `suspend`,
  `handleStatusAction` is the `Status` event. The room hears `join` / `leave` after the joiner's `state`.
- Replay is gone: `needsHistory`, `historicizableEvents`, `historyBufferSize`, `join(actor, lastEventId)`,
  `call.lastEventId`. A reconnect is a join, the snapshot is the truth. `channelCapacity` is 64.
- `ChatRoom`: `Chat` is the event (`text` in, `seq` / `from` / `author` / `timestamp` added by the
  room), persisted in seq order by one writer after emission (`persist()` is its last stage).
  `Chat` and `ChatState` live in `kroom-common` (`com.republicate.kroom.event`), the browser's
  too; `Chat.toMessage()` / `ChatMessage.toChat()` are the server's extensions.
- `trace(event)` at every stage change, `emitted(event)` after the frame; `Actor.tab`.
- The wire is the payload: no `_` metadata on frames (the stage, `from`, `tab`, `seq` were there;
  the seq is the frame's `id`), and a `_` a client sends is dropped at birth. The reply of
  `POST /rooms/{room}/act` is the verdict and nothing else: `200` `{}` or `400` `{reason, args}`;
  a rejection is framed to nobody. Validation refuses a field beyond the declared (`undeclared`).
  `Event.emit` accepts an applied event only.
- Presence on the wire is a user's: `join` is framed for a user's first connection, `leave` for
  the last, after `leaveGrace` (1 s, overridable) so a reload or a blip is no departure; the
  handlers still hear every connection (`first` / `last`), the last one after the grace.
- The instant: a step of the loop numbers and writes what it produces before it returns (a
  handler's emissions and the event's own frame are no longer re-enqueued behind a waiting join,
  which saw the change in its snapshot and then again as frames); the joiner's snapshot precedes
  its own `join` and the join handler. `ChatRoom.sendChat` is a step of the loop (`suspend`).
- `kroom-common` is now an `api` dependency of `kroom-server`.
- **The contract** (`kroom-common`): `State` (a view over the room's JSON, `reduce(frame)` its
  one writer, fields `private set`), `Logic<S>` (`registry`, `fire(state, actor, intent):
  Outcome<Event>`, `cancel`), `Outcome` = `Reason` | `Fired` (`fired(…)`, `held()`, `done`,
  `releasing(users)`), `Actor` as a common interface the server's connection implements (`id`,
  `name`, `client`), `Registry` (was `Catalog`: the intents, and through `fires` every name;
  `names` postable, `all`, `frame(name, json)`; presence arrows `join` / `leave` / `status`).
  `Event` declares `fires = Fires(::Round)` and `pending`; `Event.to(users…)` names a frame's
  viewers; fields are `in` or `.out` (the loop's, `undeclared` if sent) and may be `.mine` (the
  author's tabs only, `payload(author)`); new kinds `obj(::View)`, `list<T>()`, `log(::View,
  max)` (append-only, `+=`). `describe()` carries `pending`, `fires`, `out`, `mine`, `postable`.
- `LogicRoom<S : State>(id, state, logic)` (`kroom-server`): runs the rule on the loop, frames
  the intent then what it fired (each name checked against `fires`, a stray one is `error`),
  folds every room-wide frame into the state (`fold`; a frame with `to` is a message, not a
  transition of the shared marking), keeps the held tokens (`pending`, `already_pending`,
  `cancel` → `not_pending`), answers `{pending: true}` when an intent is held. Every snapshot
  carries `users` (presence), `pending` (the viewer's) and `me` (the viewer's presence entry,
  absent for a spectator; 2026-10-17). `Room(id, registry)`, `describe()` =
  `{state, events}` (what `GET /rooms/{room}/events` serves now), hooks `fold(frame)` and
  `follow(intent)`.
- Presence on the wire: `Join` / `Leave` / `Status` / `Cancel` live in `kroom-common`; `join` and
  `leave` carry `user: {id, name, status, clients: [{id, kind, status}]}` and `count` (`displayName`,
  `spectator` are gone, `first` / `last` stay for the `on()` path); `status {status}` posted by a
  client frames the user's composed status (`Presence`: `ONLINE` / `IDLE` / `AWAY`) and the client
  that changed; `Actor.status`, `Actor.kind`. `Table`'s `Status` is `SeatStatus` (`seat_status`).
  The deprecated `Actor.id` / `Actor.userId` aliases are gone (`id` is the user's).
- `ChatRoom` is a `LogicRoom`: `ChatState.history` is a `log(::Chat, 50)`, the fold of the
  `chat` frames, seeded from the store's tail; `ChatLogic` numbers and signs; `sendChat` goes
  through `act` under the author's id; no `users` frame of its own any more (presence is in the
  snapshot). The verdict is read as rejected-or-not (`Route.intents`, `sendChat`): a store could
  move a chat on to `PERSISTED` before the reply was written and turn it into a `400`.

#### kroom-webapp-assets
- `intents.js`: a trigger is busy for the span of its POST; the reply is the verdict (`200`
  frees, `4xx` frees and shows `reason` + `args` after it). It no longer listens to the stream
  (`attach`'s `sse` is ignored). `api.js`: the tab id is taken out of `sessionStorage` at load and
  put back on `pagehide` (a duplicated tab makes its own); `act` rejects with `.data` =
  `{reason, args}`. `sse.js` sends no `lastEventId` any more (no replay).

#### kroom-examples/chifoumi
- On the contract: one room, `ChatRoom<LobbyState>` with `ChifoumiLogic` (chat composed through
  `ChatLogic`). Alphabet `chat`, `match` (pending: a deposit while nobody else waits, paired by the
  next one; `player`, `match` out), `play` (pending, `move` mine, `player` out) `-> round {match,
  round}`, `leave -> match` (a forfeit); `cancel` withdraws a match wanted or a move. The state
  (`history`, `queue`, `matches`, `stats`) is the fold of those frames on the server and in
  `static/js/state.js`; `join_queue` / `leave_queue` / `queue` / `waiting` / `result` / `activity`
  / `stats` / `connected` frames and the `/api/match`, `/api/stats`, `/api/lobby` endpoints are gone
  (the snapshot has it all; the feed, the counts, "who is waiting" are derived). Players are user
  ids on the wire, names beside (`name1` / `name2`). The lobby page has the room's chat box and
  reports presence (`status`); `MatchStats` / `ActivityEvent` are gone, `Match` is `Game`.
  `LobbyTest`: the rule as a function, and a recorded match, whose fold through `Store` must be
  the snapshot the room would serve (commutation); the recording is the golden in
  `chifoumi-common/src/commonTest/resources/frames.jsonl` (the test says when to refresh it),
  replayed by `FoldTest` on the JVM and on node and by the pages' jsdom harness.
- The lobby page runs on the Kotlin binder: `chifoumi-common`'s js target is the app bundle
  (`arena.js`, the fold, `kroom-client` and `Main.kt` = the `match` derive + the feed reactions;
  copied into `static/js` by `processResources`), `index.html` is `data-k-*` markup over the room's
  JSON (`users.length`, `me.name`, `queue∋@me`, `history` as a `<template>`, `stats.*`, a
  `data-k-follow` link to the game when the viewer's match is on), `chifoumi.js` keeps only login
  and language. `PagesTest` checks the template's bindings against `LobbyState` and the registry;
  `pages.mjs` runs the lobby on the bundle (`BUNDLE=` for a dev build).
- The game page too: `game.html` is bound to `game`, the viewer's perspective over their match
  (names from their side, `phase` = `choose` / `waiting` / `reveal` / `over`, the round revealed
  for two seconds, the sentences composed from `#messages`), derived in `Main.kt` from the
  marking and what the page alone remembers (the match once the room dropped it, the move this
  tab played, a note); `game.js` opens the stream. **Who has played the round in progress is the
  fold's** (`MatchView.played`, from `play` and its `cancel`; the `round` frame's match starts
  afresh): the server's snapshot carries it, so a reconnect lands mid-round knowing both sides;
  the page no longer keeps a `pending` list. The chosen move is shown beside the take-back
  button instead of a highlighted disabled button. `state.js`, `sse.js` and `intents.js` leave
  the chifoumi pages (the JS mirror of the fold is gone); the jsdom harness runs both pages on
  the bundle.
- `kroom-examples/chifoumi-common` (multiplatform, JVM + JS): the lobby's declarations, fold and
  typed layer (`Lobby.kt`, `LobbyLogic`, `Move`) moved out of the server module, one class for
  both runtimes (`LobbyLogic.registry` is the alphabet, the browser's too).
- Enum fields read whatever the case (`move: "rock"`), the JSON stays as sent.
- `held()` with nothing fired: a no-arg overload (a vararg of an inferred `Nothing` built a
  `Void[]` the JVM refused).
- (before the port) A match goes to the first player with 5 won rounds (was best of 3, with draws overflowing
  "Round n/3"); the `round` frame carries the match, the page shows the server's score and round
  number instead of counting its own; a round says why it went that way ("Paper covers the rock.",
  translated with the page); the match result reads the frame's `match` (it showed "undefined wins").

#### kroom-server
- (superseded above) `handleAction` called by `Room.act` on the loop, `accepted` / `rejected` frames.
- The loop never waits on a consumer: a connection whose buffer is full is dropped (`trySend`), its
  stream ends and the browser reconnects with `Last-Event-ID`. The buffer holds `max(64, 2 × historyBufferSize)`.
- A connection id already present in the room is the same tab back: the newcomer replaces it, the
  stale `leave` is a no-op (no leave/join flicker on reconnect). Compose ids with
  `Actor.connectionId(user, tabId)`.
- `join` and `leave` run on the loop: the newcomer's `state` frame precedes the join hook's broadcasts
  (they used to race, a hook's `activity` could land before the snapshot) and any frame queued after
  the join; a frame queued before it never reaches it. `onActorJoined` / `onActorLeft` (and the
  spectator pair) now run on the loop: no awaited I/O. The snapshot is per viewer, `stateToJson(actor)`
  (defaults to `stateToJson()`): `sendStateTo` is gone, `Table` overrides the former instead; a hook
  that used to resend the joiner its own context moves that into the snapshot.

#### build — Gradle 9.4.1 (2026-11-01)
- Gradle 8.9 → 9.4.1, Kotlin 2.4.0 → 2.4.21 (`apiVersion` 2.2), velocity `3.0.0-BETA-20261101-01` (its compiler
  plugin is built on 2.4.21: the Kotlin patch of kroom follows the engine beta), AGP 8.5.2 → 9.2.1 (the last one
  running on Gradle 9.4), Dokka 1.9.20 → 2.2.0 (V2), ben-manes versions 0.67.0 under `io.github.ben-manes.versions`.
- Publishing: the vanniktech `com.vanniktech.maven.publish` plugin (0.37.0) replaces nexus-publish and the hand
  wiring (per-publication `dokkaJar`, `Sign` ordering, `-Prelease`): it builds the publications, sources and javadoc
  jars for every module; the root keeps only the pom. Signatures only when the invoked task names Central
  (`publishAndReleaseToMavenCentral`), so `build` and `publishToMavenLocal` never prompt. The examples and the
  demo are no longer published. `kroom-velocity-l10n` and the starter now ship sources jars.
- kroom-view on `com.android.kotlin.multiplatform.library` (mandatory with AGP 9): `compileSdk` 35, resources
  opted in, the activity finds its WebView by id (view binding is not a library feature on that plugin). The
  Android variants carry `libraryelements=aar` and `jvm.environment=android`, `viewbinding` is no longer a
  dependency.
- `GenerateTranslatedTemplatesTask` is `@CacheableTask` with relative path sensitivity (Gradle 9 validates
  plugins strictly). `kotlin.publishJvmEnvironmentAttribute=false` is gone: the attribute is published.
- Catalog pruned of the 24 aliases and 12 versions no script referenced. Kotlin/Native refuses commas in
  backticked test names: 17 tests renamed.
- Sites written by the starter get the 9.4.1 wrapper.
- Dependencies: essential-kson 2.18, kotlin-logging 8.0.4 (prints one `initializing` line to stderr at first use),
  slf4j 2.0.20, bouncycastle 1.86, greenmail 2.1.15, junit-jupiter 6.1.3, nimbus oauth2-oidc-sdk 11.38.2, web-push
  5.1.2 (kroom-webapp-push now declares `httpasyncclient`, which web-push moved to runtime scope while
  `PushService` reads the `HttpResponse` itself), androidx core 1.18.0 / appcompat 1.8.0 / webkit 1.18.0, material
  1.14.0, kroom-view `compileSdk` 36 (core 1.19 wants 37, not installed).

#### velocity 3.0.0-BETA-20261105-02
- Bumped: `Template.header` (the `##@` declarations: name, type, whether a default) answers on every form, a
  build-time-compiled class included — what kroom asked for a region's roots.

#### velocity 3.0.0-BETA-20261105-01
- Bumped: `HtmlDomSink` takes a fourth parameter, `clean: (key) -> Boolean` (default never) — a logged item whose
  key is clean is moved into place and kept, its body not rendered (the door for kroom-client's targeted merges);
  `SafeText` is markup on the DOM sink (a range read like static text, rebuilt whole when the string changes);
  a block or markup site at an item's own level is now removed with the item. `Room.attach` passes the key
  positionally, unaffected by the trailing lambda now binding to `clean`.

#### ktor 3.6.1
- Bumped from 3.5.0, no change on kroom's side. Brings KTOR-9790: the SSE heartbeat belongs to the session
  and stops when its handler returns.

#### velocity 3.0.0-BETA-20261026-01
- Bumped: an engine built with no interpreter (`VelocityEngine(null, …)`) renders compiled templates only and
  ships no parser; chifoumi's browser engine is one, its bundle down to 132 KB gz (242 before). The output
  syntax header is now `##@output web` (with a colon it declares a variable).

#### velocity 3.0.0-BETA-20261025-04
- Bumped: a performance fix, no API change.

#### velocity 3.0.0-BETA-20261025-03
- Bumped: the engine's `HtmlReader` (kroom's segmentizer rebuilt over the escaper's automaton) and
  `velocity-engine-dom` (`Tree`, `HtmlDomSink`, `JsTree`); the classic facade's `DynamicEscaper` escapes again;
  a build-time compiled class carries its `##@` meta. The segmentizer left `kroom-common`: l10n's fragment
  translator reads through the engine's `HtmlReader`, skips `<pre>` text by tag (ordinary text to the engine)
  and still translates `<title>` (raw text to it). The spike runs on the engine's `HtmlDomSink` and `JsTree`
  (`velocity-engine-dom` in the catalog): its own sink, block macros and tree are gone, the page is on
  `#foreach` / `#if`, the engine escapes the text render; the scenario over the client fold is what stays kroom's.

#### velocity 3.0.0-BETA-20261025-02
- Bumped: the compiled pipeline asks for the insertion seam at render time (a handler set as an instance sees every
  typed reference), the `OutputSink` contract with block and item boundaries on `#if` / `#foreach` / dynamic `#parse`,
  `RefSite.tag` / `.attribute`, the render cache replaying a structured stream. The spike drops its two workarounds:
  the handler is no longer named in the build and is a Kotlin `object` again.

#### velocity 3.0.0-BETA-20261025-01
- Bumped: the common facade takes event-seam instances, lazy native macros get their authored argument text on
  the compiled pipeline, a bare operator between macro arguments is a parse error, Map implementers navigate by key.

#### velocity 3.0.0-BETA-20261024-01
- Bumped; the beta's dynamic escaping (the reference insertion seam told the output mode) is not used by
  kroom. Every module's tests pass on it.

#### velocity 3.0.0-BETA-20261007-01
- One resource manager serves every named resource, root or `#parse`d child, kept as its loader's `cache` /
  `modification_check_interval` say: a computed `#parse($name)` is re-read when its file changes (a dev-edited
  region showed only after a restart — reported from site2026; pinned in `DevDirTest`).
- The 3.0 loader contract is `ResourceLoader { find(name): Content? }` (`org.apache.velocity.engine.resource`),
  null meaning *not mine*, a `Content` opening its text and knowing whether it changed: `MarkdownConfig.loader`,
  `DirectoryLoader` and `ResourceStore` follow — `load`/`exists`/`lastModified` are gone, `content(text)` stands
  in for a loader written as a lambda. The block engine lists its store as the `blocks` loader
  (`markdown.resource.loader.blocks.*` for its cache policy; nothing kept by default).
- `resource.loaders` is the whole truth: `VelocityPlugin` lists `stock` — the templates a build compiled —
  first in production, after the files in dev (an edited source wins). kroom-velocity-l10n points the build
  at its generated tree with `resource.loader.stock.sources` and `velocimacro.library.path`
  (`templateRoot`/`macroLibraries` are gone from the extension).

#### kroom-webapp-authoring
- An authored page has no `header`, `west` or `footer` block any more: those regions are the site's (its theme's
  partials, worded by the `name` and `footer` settings), never a page's. The handle on them opened an empty
  editor over a default a block could not reproduce. A block written there is ignored.
- A page is born where it goes: the pages panel's bottom form is a phantom row dragged into the tree, which
  asks the name and the layout and creates the page under that list, at that index (a link is stored there the
  same way). The layout is chosen at birth because the seed runs at birth: a `gallery` page is born with its
  gallery.
- The pages panel reloads the page after every action — the menu, the west, a TOC are server-rendered — and
  the admin bar reopens where it was (panel and entry, `sessionStorage`); a newborn page is opened.
- The admin bar's panel slides in and out (CSS, `allow-discrete`; none under `prefers-reduced-motion`); a click
  on the page closes it. Its last button is the eye: pressed, the page as visitors see it; released (struck
  through, remembered per tab), every block outlined, the empty regions with them. The site's first group of
  settings is *General* (was *Site*, the panel's own name); a settings form's groups fold, the first one open.
  A theme's settings are on the themes panel, under the theme, worn or not (the preview shows them); the plugins
  panel lists plugins only (`GET /api/site/themes` carries `settings`, `/plugins` no theme).
- webmaster: every link a page carries is checked, the other sites' too — the `external` setting is gone (a
  link is a promise whoever it points to).
- The admin bar is trimmed to what one acts on: site, pages, plugins, themes, roles, the eye. The journal's and
  the media's entries are gone (the journal's API and panel code stay, the media panel is removed — the media
  API is the editor's and the gallery's). A plugin's `AdminEntry` of tables alone is no icon any more: its
  tables show under the plugin in the plugins panel (`AdminEntry.standsAlone`: a link or a frame keeps its
  icon); `GET /api/site/plugins` carries them (`tables`). forms lists its messages nowhere (`/api/forms/messages`
  stays). The site's *Description*, *Public URL* and *Picture* are a group of their own, *Sharing*.
- `Plugin.holds(site)`: what a plugin holds that pages show, or null — the plugins panel asks before switching
  off one that answers (gallery: "3 galleries, 41 pictures"). Pinned in `GalleryTest`, with what happens then: the
  blocks calling it say they cannot be displayed (blocks are strict, the call is an undefined reference).
- Saving a settings form, switching a plugin, reloads the page — it was rendered with the old values (the basic
  theme's dark scheme seemed to do nothing) — and the bar reopens on the plugin or the group one was in.
- Logged in, one is back where one was: serving its login page, the site remembers the page it was reached
  from (`kroom_from`, a short-lived cookie); `POST /login` goes there, or where a `from` field says.
- A root page is born with its content, the loop over its children (`AuthoredPage.TOC`): a section's page is
  a table of contents until its editor says otherwise. `kroom/page.html` hands every content block `$children`.
- (added) Warm-up: once the server listens, a content site requests `/` from itself in the background, so the
  first visitor does not pay the compilers' start (`warmUp`, on by default).
- kroom's own templates name with `_`, never `-`, so a build can compile them (a compiled template's path
  segments are identifiers): `kroom/block_wrapper.html` (the default `markdown.block.wrapper`, was
  `block-wrapper.html`), `kroom/regions/{header,west,footer}_default.html`.
- `strings` no longer rewords the editor or the admin bar: kroom ships their words (en, fr, `language`).
  `installContentSite { strings[…] }` remains for what the bar says of a plugin, keyed by the plugin's ids;
  `installAuthoring { strings }` and `kroomAuthoring.strings` are gone.
- `$site.head()` now emits the house scripts, and the editor's for a logged-in author: a layout calling
  `$kroomAssets.coreScripts()` and `$authoring.assets.tags()` besides must drop them, or load them twice.
- `installContentSite { store = … }` is now `storage = …` (a `Storage`; its `content` is the former store),
  and `canEdit = { session, path -> … }` is gone: `identity = IdentityProvider { session -> roles }` answers
  roles, `roles` (a `Roles` table) says what each may do, and editing asks `content.edit` on the block's path.
  `installAuthoring` takes `identity`/`roles` likewise. The default provider knows nobody: nothing is editable
  until one is given (it used to be any session).

#### kroom-markdown
- The default ACL also refuses `$x.class` (`- java.lang.Object#getClass`): velocity leaves it to the ACL as
  2.x-legitimate, but a block has no business with reflection.
- A block's `%foreach` stops at 10 000 turns by default (`directive.foreach.max_loops`, unlimited in velocity):
  a block runs before anyone reviewed it. Each loop its own count.

### Added

#### kroom-common — a lobby's State and events
- `Roster`: a room as its lobby shows it, `id` and `users` (keyed `UserPresence`). Open: an app's subclass adds fields.
- `LobbyState(roster)`: `rooms`, the rosters of the rooms that show. The base's `users` is who watches.
- `LobbyEvents(roster)`: `show {roster}`, `hide {room}`, `enter {room, user}`, `present {room, user, status}`,
  `exit {room, user}`. None is postable.
- `RosterEvent`: the base of an event about one room (`room`). An app's own extend it and its `LobbyState` reduces them.
- `LobbyLogic` decides nothing. `LobbyState()`, `LobbyEvents()`, `LobbyLogic()` are a lobby of plain rosters.
  Pinned in `LobbyTest` (common).

#### kroom-server — `Lobby`, a room of rooms
- If your page shows who is in which room of a group, `Lobby` gives it one stream for the whole group.
- `Room`, `LogicRoom` and `ChatRoom` take a `lobby` as last constructor argument, null by default.
- A room's roster appears with a `show` frame at its first user, and goes with a `hide` frame when the room closes.
- The room's `join`, `status` and `leave` frames reach the lobby as `enter`, `present` and `exit`.
- `Room.show(roster)` answers false for a room that must not show. Nothing of it reaches the lobby, not even an
  app's `RosterEvent` about it.
- `Room.announce()` asks `show` again, after the room's access changed.
- `Lobby.rooms` lists every live room of the group that `Rooms` holds, shown or not. Server only.
- A lobby built on demand stays open while one of its rooms is live.
- `Rooms.getOrCreate` may be called from inside a `build`: a room's build fetches its lobby.
- `Room.emit` is `open`.
- Only rooms that show to every watcher are covered: showing a private room to its members alone is not built.
  Pinned in `LobbyTest` (server).

#### kroom-webapp-assets — `t(en, vars)` for hand-written scripts
- `i18n.js` (`/js/kroom/`, `KroomAssets.i18nScript()`, not in `coreScripts()`): `window.t(en, …)` answers
  `window.i18n[en]` (read at each call, an object msgid → translation) or `en` itself, then fills its placeholders —
  `{name}` from an object, `{0}`, `{1}` from the further arguments; one without a value is left as written. Pinned in
  `i18n.mjs` (`smoke.sh`).

#### kroom-webapp-l10n — a request's language pinned by the host
- `ApplicationCall.language` is settable: `call.language = "fr"` pins the request's language, whatever the session
  or the URL says; `$lang` and `$jsTranslations` follow. A language that is not configured is refused
  (`IllegalArgumentException`). Pinned in `PinnedLanguageTest`.

#### kroom-webapp-l10n — a language's catalog adds up across po files
- `PoTranslationSource.loadBundle(lang, path)` adds the file's entries to the language's catalog instead of
  replacing it; for a msgid two files hold, the first loaded wins. A missing file adds nothing.
- `Application.addCatalog(path)` loads `<path>/<lang>.po` for every configured language but the source one into
  the installed PO source; false when l10n is not installed or its source is not PO files. `installL10n` loads
  the application's own files through it, first: a library's catalog fills, never overrides. Pinned in `CatalogTest`.

#### kroom-client — a region in a language the engine lacks
- `VelocityEngine.localized(lang) { "$it/lobby.html" }` names a region in the page's language as far as the engine
  holds it: `lang`, else its primary tag (`fr` for `fr-BE`), else English (`fallback`). A page whose language its
  regions were not compiled for no longer throws at `attach` (`template "fr/lobby.html" not found`): a server's
  languages and a client library's are not the same build.
- `pageLanguage()` (js): `<html lang>`, English when it says nothing. `localeOf(lang)`: the locale dates are
  written in — plain `en` is `en-150`, day first and 24 h, never the US's; a region the page names is kept.
- chifoumi's regions and its chat's times go through them. Pinned in `LanguageTest`.

#### kroom-webapp-assets, kroom-webapp-authoring — the markdown toolbar, shared
- `markdown.js` / `markdown.css` (`/js/kroom/`, `/css/kroom/`; `KroomAssets.markdownScript()` /
  `markdownStyle()`, not in `coreScripts()`): `window.kroomMarkdown` — `toolbar(target, {strings, icons, extra,
  language})` (a `div.kroom-format` of icon buttons; `target` a textarea or a function answering one),
  `shortcut(event, textarea, extra, options)`, `formats`, `replace` / `wrap` / `prefix` / `link` / `code`, the
  words (en, fr), the pictograms and `icon(name, icons)`. An edit is heard as `input` whether `execCommand` did it
  or the `setRangeText` fallback; a button no longer takes the focus from the textarea.
- The block editor is built on it: its toolbar is that one plus `image`, its words and pictograms merged into
  `kroomAuthoring.strings` / `.icons` / `.stock` as before. `AuthoringAssets` emits markdown.css and markdown.js
  ahead of its own, so a site mounts `kroomAssets()` (`ContentSite` does). `.kroom-icon` and `.kroom-format`
  moved from authoring.css to markdown.css. Pinned in `markdown.mjs` (assets' `smoke.sh`), `smoke.mjs`, `AssetsTest`.

#### kroom-common, kroom-client — a text written by several
- `TextState` / `TextEvents` / `TextLogic`, beside the chat's: a text as parts in order (`parts`, a `seq` of
  `Part(id, text, holder, client, caret)`), one writer per part, a part held by a client (a tab). The page computes
  nothing; the fold decides what the text is made of. `take {ids}` (free, adjacent: they become one part, held;
  `held`, `gone`, `scattered`) or `take {after}` (a part born after that one, held: a paragraph begun) — a take
  gives back what the client held, in the same frame; `write {id, text, caret}` (the part held says this, whole;
  `not_held`, `gone`); `release {id}` (cut into its paragraphs by `Part.cut`, a fenced block kept whole, the new
  ones born after it; left blank it goes, the last one stays). A late view is refused, never applied askew; a
  frame is a state, never a difference: nothing to replay. What a client held is released when its leave is heard.
  `state.text`, and `TextState(text)` for a room rebuilt from what a store kept. No room class and no store: a
  plain `LogicRoom`, `persist(event)` the application's. Pinned in `TextTest` (common), `TextRoomTest` (server).
- kroom-client: `room.write(template, root)` — the region and its `Writer` (the textarea of the part held posts
  `write` as typed, `take {after}` at a paragraph begun, `release` on leaving it or after five idle seconds; a
  text's intents leave one after the other, the region's triggers in the same turn) — and `TextPage`, the page
  memory (`mine(part)`, `holder(part)`). `Room.attach(template, root, post)`: a region's triggers may post through
  a turn of its own.
- Who may write is a room's to say: `Take` is open and `TextEvents(take = ::Take)` takes a room's own, a subclass
  whose check reads the room's State (its editors, a text closed) — run after the text's, in the browser before
  the post and on the loop. A text that closes emits a `Release` for each part still held, as the leave does: what
  a logic emits is not checked again. `TextPage` is open, for a page memory with more. Pinned in `TextTest`,
  `TextRoomTest`.
- Two rooms on one page (a text over a chat) pinned in kroom-client's `TwoRoomsTest`: a stream each under one tab
  id, each root's triggers posting to its own room, presence reported to each. Roots must not nest; the `kroom`
  JS facade holds one room.

#### kroom-client-text, kroom-webapp-assets — a text's region and stylesheet, for any application
- `kroom-client-text` (new, for the browser alone): the text's region compiled under velocity's prefix `kroom` —
  `kroom/text.html`, registry `velocity.templates.kroom.VelocityTemplates` — so an application gives its engine
  kroom's registry beside its own (`StockResourceLoader(mine::resolve, kroom::resolve)`) and either writes in it
  (`room.write("kroom/text.html", root)`) or `#parse("kroom/text.html")` from a region of its own handed to
  `room.write`. Built with `compat.informal_navigation`: whoever shares an engine with it builds with the same
  settings. Apart from kroom-client, which has a JVM target: compiled templates publish their build settings to
  every engine on a JVM classpath.
- kroom-webapp-assets: `/css/kroom/text.css` (`KroomAssets.textStyle()`), the parts under an element of class
  `kroom-text` — what was the scratchpad's own, no authoring needed.

#### kroom-plugin-scratchpad — a text written by several, to try in a page
- An authoring plugin, unpublished, in the demo at `/scratchpad`: `$scratchpad.show()` is the place of one text
  for the site, kept in memory; whoever is logged in writes, a visitor watches. Two modules: the plugin (the room,
  its routes under `/api/rooms/scratchpad`, the tool, the stylesheet of what is around the text) and its `page`
  (the browser's entry over kroom-client-text's region, bundled as `/js/scratchpad.js`, 136 KB gz).
- A bar over the text: the stock markdown toolbar (kroom-webapp-assets' `markdown.js`), acting on the part this
  tab holds and dim while it holds none, its shortcuts in the textarea; and a preview, the text as html beside the
  text (under it on a narrow screen), asked when it opens and a moment after each frame that moves the text while
  it is open. Neither button takes the focus: looking is not leaving.
- `$scratchpad.html()`: what the text says now, as html (`kroom-markdown-core`), for any page to show — the demo's
  `/scratchpad/read`; `GET /api/scratchpad/html` is the same, what the preview asks. The room keeps the text as of
  its last frame on the loop (`emitted`), so a page or a route reads it from anywhere.
- The demo's container builds, then runs what it installed (`installDist && exec …/bin/…`), the host's Gradle home
  mounted read-write: nothing holds a Gradle lock while the site is up. The private project cache is gone.

#### kroom-common — an array field in the markup
- `Field.parse` reads an ARRAY field's text as its items separated by blanks, as html writes a list in an
  attribute: `data-kroom-ids="3 4"` posts `ids: ["3", "4"]`, so a click takes a part of a text with the stock
  binding. Pinned in `EventTest`.

#### kroom-common — `seq`, a field whose order is state
- `seq(::V)`: views found by their `id`, in an order that is itself state (an array on the wire), where `keyed`
  keeps the order of birth and `log` only appends. `Seq.splice(index, count, items)` is its one writer — an
  insertion, a removal, a run rewritten in place; `seq[id]`, `id in seq`, `indexOf(id)`. What comes in is dirty by
  its id, what goes tells the root. Pinned in `EventTest`, `LogicTest`.

#### kroom-markdown-core — markdown to sanitized html, alone
- A new module, flexmark and jsoup only (no velocity, antlr or kotlin-reflect): `Markdown.html(source)` renders
  markdown, the GFM set on, through the allowlist `kroom-markdown` blocks pass. `Markdown.html(source, before,
  holds)` lets a caller write on the tree before the allowlist and keep its own `data-kroom-slot` stand-ins
  (`Markdown.SLOT`). `kroom-markdown` builds on it, its blocks rendered byte for byte as before; nothing changes
  for its dependents. Pinned in `MarkdownTest`.

#### kroom-webapp-authoring — a miss is a page of the site
- A content site answers a page nobody answered with a page of its own: after the plugins' `notFound` handlers, a
  browser's GET or HEAD for a page (`Accept: text/html`, outside `/api/`, no asset extension) gets
  `kroom/not_found.html` in the active theme's layout, status 404, `noindex`, the path asked escaped as `$missing`.
  `ContentSiteConfig.notFoundTemplate` names another (a site's own words; null: the plain 404). A page that fails
  to render is logged and leaves the plain 404. An API's or an asset's miss stays plain. Pinned in `NotFoundPageTest`.

#### kroom-plugin-gallery — photo galleries
- `Gallery(images?, strings)`: a gallery is a record of this plugin (`galleries`: its pictures in order, a caption per
  language), shown wherever a block or page calls `$gallery.show("<id>")` — a grid of square thumbnails, each figure
  a link to its slide, that `gallery.js` opens as a slideshow (scroll-snap scroller, swipe, ←/→ wrapping, autoplay,
  dots or a counter, captions, zoom and download of the original, `#<id>-<n>` deep links with Back closing) — and,
  for whoever holds `gallery.edit` (editors and authors), edited in place: upload or drop pictures (the media API),
  drag to reorder (SortableJS), caption per language, remove; every change is one `PUT /api/gallery/{id}` of the
  whole list, the reply the gallery as stored with its URLs, which the grid is rebuilt from. `POST /api/gallery`
  makes one, `GET /api/gallery/{id}` reads it, `DELETE` removes it — no admin bar entry: the plugins panel is the
  plugin's place, a gallery's is its page. Pictures pasted on a gallery page land in its gallery (in the one the paste
  happens in when the page holds several). A page born with the `gallery` layout is seeded with
  `$gallery.show("<new id>")`. Renditions come from a
  `kroom-webapp-images` `Images` the application hands over (fill 300 for the grid, fit 900 for the show, the
  original to zoom and download; its route mounted by the plugin, on a path of its own — `/img` is the static
  files'), the originals otherwise. Pictures only; videos later. Pinned in `GalleryTest`; the browser side in
  `plugins/gallery/src/test/js/smoke.mjs` (jsdom in docker). In the demo.

#### kroom-webapp-authoring — a seed per layout, the `gallery` layout, the upload ceiling
- `Site.seed(layout) { page -> body }`: what the content block of a page born with that layout starts as — a
  plugin's call (`$gallery.show("k7f3a")`), signed by the page's author; a root page's table of contents stays the
  default when no plugin seeds its layout. Pinned in `PagesTest`.
- `gallery` joins the skeleton's layouts (`SKELETON_LAYOUTS`, the dummy theme): a page that is a gallery.
- `installContentSite { mediaMaxSize = … }` surfaces `AuthoringConfig.mediaMaxSize` (10 MB by default).

#### kroom-webapp-authoring — the authored page's template
- `installContentSite { pageTemplate = … }` names the template an authored page is rendered through (`$authored`,
  `$kroomPage`), `kroom/page.html` by default; `Site.pageTemplate` reads it. The door for an application that wants
  its regions otherwise — a block per language with a fallback, say — without a core that knows about languages.

#### kroom-webapp-authoring-git — the content store as a git repository
- `GitResourceStore(root, committer, domain)`: a `FileResourceStore` that is `Versioned` through a git repository
  under the content root (its own `.git`, hidden from `list` and refused as a path). Every write is a commit by the
  block's author (`meta["author"]`, else the last one, else the committer), a deletion and a move too; the same
  body again is none; content found under the root at creation is the first commit. `log(path)` follows renames,
  each revision under the name it bore, so a moved block keeps its past and an old revision answers under the new
  path; `log()` is the journal, newest first, with the path each commit touched. A git failure after the file is
  written is a warning, never a lost edit. Moved up from site2026, where it was app-side. Pinned in
  `GitResourceStoreTest`. JGit stays in this module: authoring itself gains no dependency.

#### kroom-common, kroom-server — the leave's `seen` and `read`
- `Leave.seen`: the room's position when the user's last connection dropped — the SSE id of the last frame its client
  may have received; the graces' frames and the `leave` itself come after it (chifoumi's golden refreshed). On every
  leave the handler hears, the position at that connection's drop.
- `Leave.read`: the room's own numbering then, when it has one — `Room.read` (`protected open val`, null by default),
  a `ChatRoom`'s `state.last`: the last line said as of the drop, what a store marks the user read up to
  (`persist(Leave)`). The frame counter restarts with the room; a chat's lines continue the store's — a consumer
  marking lines wants `read`, not `seen`.

#### kroom-server — a closed stream is left at once
- `room(…)` / `rooms(…)` install ktor's `HttpRequestLifecycle` (`cancelCallOnClose`) on the stream route alone: a
  stream the client closes is cancelled at once on Netty and CIO (a proxy's `RST_STREAM` included), the room's `leave`
  runs and the graces count from the close, not from the heartbeat's failed write 15–55 s later. The app's other calls
  are not cancelled. `Room.leave` waits for the room under `NonCancellable`: a cancelled handler still returns once the
  room has forgotten its connection. Pinned over a real Netty socket in `RouteTest`.

#### kroom-webapp-images (new)
- `Images(imgproxy, cache, local)` + `Route.images(images)`: images resized through imgproxy v3 and kept on disk
  (`<cache>/<2 hex>/<sha256>-<rendition>.webp`), from local files and remote sources (fetched server-side); sizes
  snap to `fill` 32/64/128/300 and `fit` 600/900, the original beyond; a local file newer than its rendition, or a
  remote one older than `remoteTtl`, is made again; imgproxy down serves the original, a remote unreachable its
  last copy. Remote sources only through `images.url(src, size)`, HMAC-signed (key given or `<cache>/secret`); a
  remote original must be png, jpeg, gif, webp or avif. Responses carry `nosniff` and `default-src 'none'`. Moved up
  from gallope's `serveImage` (which resized on every request, no cache, jpeg only).

#### kroom-common, kroom-server — the avatar on the line
- `Actor.avatar` (common, null by default), the server's from its `User` (`User.avatar`, a `var` read when asked,
  so a new picture reaches connected tabs; `Users.getOrCreate(id, name, avatar)`) or from its constructor;
  `ChatLogic` writes it into the line's `author` when present, `sendChat(Author(id, name, avatar), text)` carries a
  bridge's, so it reaches the frame, the tail and the store.

#### chifoumi
- The pages on `Room`: the server renders the chrome (head, nav, login, the sentences `say` reads, now
  `data-kroom-msg` / `data-kroom-verdict`), the room's parts are regions — `online.html`, `lobby.html`,
  `round.html`, `match.html` in `chifoumi-common/src/commonMain/pages`, translated per language at build
  (`fr.po` moved to `chifoumi-common`, the server reads it from the jar) and compiled to common classes
  (`velocity.templates.<lang>.*`) merged in the browser. `LobbyPage` (the feed, the viewer's perspective) and
  `GamePage` (the kept match, this tab's move, the reveal) are page memory in common code; navigation and the
  chat's scroll are reactions; intents on `data-kroom-intent`. Stats start at zero. `RegionsTest` (JVM): each
  region's tree is its text render after every recorded frame, the counter and the chat line keep their nodes;
  `pages.mjs` runs both pages on the real bundle. `PagesTest` (the `data-k-*` check) goes: the typed headers
  make an unknown name a compile error. The bundle carries the engine: 242 KB gz (77 KB before).

#### kroom-webapp-l10n
- `translateTree(templates, i18n, source, languages, out)`: a template tree translated into `<out>/<lang>/<rel>`,
  literal `#parse` targets relocated; the velocity-l10n plugin's task runs on it, and its `main` serves a build
  that cannot apply a plugin of its own repository (chifoumi-common's pages).

#### kroom-client (new, KMP jvm+js)
- The binder: a page is a function of the room's JSON. `commonMain`, tested on the JVM and on
  node: `Path` (`stats.played`, `users.length`, `@me` = the snapshot's `me.id`; keys and indices,
  not expressions), `Show` (`p`, `p=lit`, `p!=lit`, `p∋lit`, any behind `!`; `@me` as a literal),
  `Bindings.scan` (the `data-k-*` attributes off markup, template bodies flagged relative) and
  `Bindings.check(html, registry, state)` (a root path's head must be a state field or a room key
  — `users`, `pending`, `me` —, an `on` must name an event: the naming class caught at build).
  `jsMain`: `Client(state, registry, room)` opens the stream, folds through `Store`, runs
  `derive` then `on` reactions, flashes `data-k-on` elements for `data-k-for` ms against the
  frame, projects `data-k-field` / `data-k-show` / `data-k-each` / `data-k-follow` writing only
  on change; `Intents` posts `[data-intent]` (busy for the span of the POST, the reason shown
  after the trigger, a form's fields and `data-intent-of` in the payload) through `api.js`,
  which stays the one owner of the tab id; `say(key, vars)` reads the page's `#messages`. A
  `@JsExport` facade `kroom` (`on`, `act`, `say`, `connect`, `frame`) for plain-JS pages.
- `Room(name, state, registry, engine)`, the v4 client (`private/eventsv4.md` §5): `attach(template, tree, root)`
  adds a region, a page handed to the engine's `HtmlDomSink`; `frame(name, data)` folds through `Store`, then into
  the `page` memory (a `State` of the page's own), merges every region again (only what differs is written), then
  runs the reactions (`react { name, event -> }`, effects after the merge); `merge()` after a page event; roots
  `state`, `me`, `page`.
  `key(value, index)`, the sink's item identity from the declarations: a view's `id`, a logged event's `seq`,
  else the position. `jsMain`: `attach(template, root)` over `JsTree` with `[data-kroom-intent]` triggers,
  `connect(events)` (the stream, `api.js`'s tab id), `act`. `Intents` takes its root, its attribute and the
  post. Depends on `velocity-engine-common` and `velocity-engine-dom`; a page is compiled by the app, the
  module that owns the templates registry. v2's binder left with chifoumi's move (the `data-k-*` grammar,
  `Path`, `Show`, `Bindings`, `Client`, `Projector`, `derive`); `Intents` takes `data-kroom-intent` alone,
  `say` reads `data-kroom-msg`, the `kroom` facade (`connect`, `on`, `act`, `frame`, `say`) sits on `Room`.
  `kroom-spike` is gone: its tree double and its oracle run in chifoumi-common over the real pages.

#### kroom-common
- `State.users`: the viewer's presence as the snapshot carries it (`$state.users` in a page); `Store.users` reads it.
- `html.Segmentizer`, the HTML reader shared by l10n and the page compiler to come: a state machine
  over strings fed in document order, so a tag, an attribute value or a `<script>` opened in one
  string (before a reference) and closed in a later one reads right; every byte lands in exactly one
  segment (`Text`, `Open`, `Attr`, `End`, `Close`, `Other`), `position` says where the reader stands
  between two strings. `script`, `style`, `textarea` and `pre` are raw. Tested on jvm and node.
- `event.Event`, the one thing that goes through E, the same class on every target: a name, a
  payload (a `JsonView` over the kson object — `val move by enum<Move>().required`, the property
  name is the key, declaring a field records it) and metadata under the reserved `_` key. Its life
  is walked in order on the object itself: `accept(from, tab)` (a door, stateless: the shape),
  `apply()` / `reject(reason)` (a room, with state), `emit(seq)`, `persist()`; a step out of order
  is the object's error, a room-born event applies from birth. A subclass is a declaration — name,
  `Audience` (`GLOBAL` / `ROOM` / `USERS`), permission, fields — that `describe()` serves as data;
  a `Catalog` rebuilds an event from a frame by name. `Reason` is a key and arguments, localized
  by the browser. Pinned in `EventTest`, JVM and JS. Nothing uses it yet (branch `kroom-events`).
- jvmTest runs with `slf4j-simple`: kson logs through slf4j on the JVM.
- essential-kson 2.16: objects compare as maps (key order blind), arrays sized, numbers by value
  (a counted `Int` 1 equals a parsed `Long` 1), so the fold tests compare snapshots as objects.
- `event.Store(state, registry)`: a room as a client folds it, on any target — `state`
  replaces, `join` / `leave` / `status` keep `users`, every other frame (`cancel` included) goes
  through the same `State.reduce` the server runs. What a viewer holds live is the app's fold to
  know, since the release rule is its logic's; the snapshot's `pending` says what was held at join. `Registry` knows `join` and `leave` (`all`, `describe()`,
  `frame()`), posted by nobody, and `status` and `cancel`, kroom's own intents, postable in every
  room (2026-10-18: the server's `on()` used to add them to the shared registry at runtime, so a
  browser's pristine copy never listened to them).

#### kroom-server
- Intents (`private/intents.md`): `Room.act` returning the verdict as a `Deferred`, `act(user, tabId,
  intent)` for routes, `Route.intents { call -> user }` mounting `POST /rooms/{room}/act`,
  `call.tabId` / `call.lastEventId` (header or query). Pinned in `RoomTest`.
- `ChatHistory`, where a room's chat lives beyond its RAM tail: `tail(n)` seeds the sequence and the
  snapshot when the room starts, `append` is write-behind (the loop never waits on it), `before(seq, n)`
  pages scroll-back; `MemoryChatHistory(capacity = 1000)` is the default. `ChatRoom(id, history)` numbers,
  keeps the last 50, broadcasts, then persists; `ChatMessage` carries an `Author(id, name, avatar)`
  (`from` stays in the JSON as the author's name). `Route.chatHistory { call -> user }` mounts
  `GET /rooms/{room}/history?before=&n=`. Pinned in `ChatRoomTest`.

#### kroom-webapp-assets
- `intents.js`: `[data-intent]` triggers posting `{type, ...data-*}` fire-and-forget, busy
  (`aria-busy`, `disabled`) until the `accepted` / `rejected` frame; the reason shows after the
  trigger; HTTP failure and stream `open` are the safety nets. `intents.attach({room|act, sse, store?, payload?})`.
- `api.act(room|path, type, payload)` with the `X-Kroom-Tab` header; `api.tabId()` (sessionStorage).
- `sse.js`: `?tabId=` and `?lastEventId=` on the url it opens (a manual retry after `CLOSED` keeps
  replay), `into(store, ...names)` dispatching `{type: name, payload}`.
- jsdom smoke test: `kroom-webapp-assets/src/test/js/smoke.sh`.

#### kroom-examples/chifoumi
- The proof of the intents door: every player gesture is a `[data-intent]` button, the three REST
  routes and the second copy of the matchmaking logic are gone (`intents { … }` + `handleAction`),
  a frame for a player reaches every tab of theirs, a reconnecting tab keeps its connection.
- A player's match is in their snapshot (`stateToJson(actor)`), the join hook no longer resends it:
  `match` only ever means "started", the game page reloads on it unconditionally and the lobby goes
  to the game when its `state` carries a match.

#### kroom-webapp-authoring-starter
- A site starter: a jar asking a few questions and writing a site pinned to the build it came from (its build,
  `Main.kt`, two pages, `compose.yml` on the plain JDK image run as the creator, `run.sh`, `.env`, this
  repository's wrapper);
  `republicate.com/kroom/create.sh` fetches the latest and runs it in a JRE container. `publish-snapshot.sh`
  now pushes kroom to `republicate.com/maven2` too (`~/bin/m2-push`), where a site resolves it.

#### kroom-markdown
- `MarkdownMacro.SHAPES` (`kroomShapes`): an editor names a block path in a render's context, and the render
  answers a walk of what that block sees — its arguments and tools, then one level of declared types per `.`,
  as the block sandbox lists them (velocity `-20261005-01`'s `Navigation`). Types are kept, never values.

#### kroom-webapp-authoring
- A menu entry may be a link again (`MenuItem.href`, any address with a scheme): stored in the tree with its
  words, never a page under it, never the current page, `external` for the theme. The panel's new-entry form
  takes a segment or an address in the one input.
- The editor completes `$` and each `.`: what the block sees, then the members its sandbox lets through, a
  `%foreach` variable unfolding as an element of what it iterates over. The page renders on the first `$` of
  an editing session, never on opening; arrows walk the list, Enter or Tab take a key (a call's parameters come
  selected), Escape closes it.
- `POST`/`GET {prefix}/shape/{path...}`: what a block sees, for the editor's completion. The page renders once
  (the lock keeps the walk), then each `.` unfolds a level without rendering again.
- The analytics and webhook plugins are gone: a counter's snippet is a line in a layout, and `onPublish` stays
  on `Site` for whoever wants to be told.
- Mail is the site's: `site.mailer` is SMTP (angus-mail) over the site's *Mail* settings once `smtpHost` is
  set, or the transport the application set, or null. The mail plugin is gone with its log and webmail: what a
  site sends is the recipient's business; Mailpit stays beside the demo as a debugging mailbox, on its own port.
- Authored pages are keyed by an id every store accepts (`/company/history` → `company_-history`; `_` escapes
  itself and the slash): on a `FileStorage`, a nested page failed, and so did any miss that was no page path
  (`/favicon.ico` answered 500 instead of 404).
- Authored pages: a record (`path`, `layout`, `draft`|`published`) under `site/pages`, its title and
  description its menu entry's label and description; blocks in the content store where a template's would
  be, rendered through `kroom/page.html` in the not-found phase (templates win); drafts for their editors only,
  marked in the menu, hidden from the sitemap. `POST /api/site/pages`, `PUT`/`DELETE /api/site/pages/{path}`,
  `POST /api/site/pages/move` (a page with none under it, its blocks and their past with it — refused onto a
  taken path or while someone else writes one of its blocks; no redirect left behind); a section of the
  *pages* panel offers to create its page. Every region is there to write: unwritten, a
  sliver with the edit handle for who may write it, nothing for a visitor — the site's default where there is
  one, shown until written: `#@markdown(name) … #end` (kroom-markdown) renders its body for a block nobody wrote, and the
  `kroomPage` context key names the page blocks bind under. `menuDepth`, `menuPanels` and `west` site settings.
- Roles: `editor` owns the pages, which are the menu (`pages.*`; `pages.edit` guards every page and menu
  route), beside every block; a built-in `author` writes blocks (`content.edit`) and nothing else. The admin bar
  shows whoever has at least one entry, each builtin entry carrying its permission — an editor gets *pages*.
- A breadcrumb in the skeleton, under the header, on any page inside a section: the site, the section, the
  page (`kroom/regions/breadcrumb.html`, a region partial a theme may rewrite).
- `ResourceStore.move(from, to)` and `moveAll(from, to)`: a block, or every block under a folder, changes
  place with its past — a versioned store answers the old revisions under the new path and records the move.
- A block can be trashed: `ResourceStore.delete`, `DELETE /api/content/{path}` for its editor; a versioned
  store keeps the deletion as an empty revision, so it can be restored.
- The pages are a tree, and the tree is the menu: what exists (templates, placeholder instances, authored
  pages) derived by segment, arranged and worded by an editor's stored tree of `MenuItem`s (a page's `slug`
  under its parent; `label` and `description` by language) — which never makes a page exist: a page made since
  comes after the arranged ones, an entry no page answers is dropped. `GET`/`PUT`/`DELETE /api/site/menu`; the
  bar's *pages* panel is that tree, compact, dragged with SortableJS (vendored, `static/lib/sortablejs`), each
  page an accordion (label, description, the page; layout, publish, delete for an editor's), a new page typed
  by its name. A section no page answers leads to its first page. `languages` joins the site settings beside `lang`; `requestLanguage` on
  `installContentSite` tells the site a request's language (`$site.lang`, `<html lang>`, the menu's words).
  `NavItem` gains `slug` and `resolved`; `#nav` renders descriptions.
- Themes, take two (breaking). The regions are `$header`, `$top` (was `$hero`), `$content`, `$east` (was
  `$aside`), `$west`, `$footer`; `$header`, `$west`, `$footer` have site-wide defaults (the menu; the section's
  pages; the `footer` site setting). A `Skin` is a theme of stylesheets only, over kroom's skeleton
  (`kroom/skeleton.html`), each region a partial it may rewrite (`themes/<id>/regions/`); `BasicTheme` is one.
  `#layout()` takes the `layout` site setting. `$theme` is the theme (`id`, `stylesheets`, `settings`, a setting
  by key), no longer its settings; a theme's head fragments are emitted while it is active. `#nav($items)`
  renders the menu tree, `$nav.section` names the visitor's top-level entry.
- A plugin is switched on and off live from the plugins panel (`PUT /api/site/plugins/{id}/enabled`, the
  `plugins.disabled` site setting): everything it recorded is tagged with it and falls silent while it is off,
  its routes answering 404. `Plugin.check(settings)` says what keeps a plugin from working; enabling asks it
  and answers 409 with its words.
- The site's card (description, Open Graph, canonical; a page's own through `$page.title(…)`, `.description(…)`,
  `.image(…)`) and its redirects (`redirects` setting, `{name}` captures, `@resolver` through
  `installContentSite { redirectResolvers }`, the 404s counted — two tables under the *site* entry) move from
  the webmaster plugin into the site. Webmaster keeps the outside view: `indexed`, robots.txt and the robots
  meta (`$page.noindex()`), sitemap.xml, the link check; `$seo` is gone.
- The site's own settings, declared like a plugin's and shown first in the admin bar: *Site* (`name`, `lang`,
  `baseUrl`, `description`, `image`), *Mail*, *Redirects*, plus the application's (`installContentSite {
  settings += … }`); `GET`/`PUT /api/site/settings`; `$site.name`, `$site.lang`, … and `$site.settings.<key>` in
  templates. `BasicTheme` loses `siteName` and `lang` to them (breaking for a layout reading `$theme.siteName`).
- The editor has a help button: a popover cheat sheet of the markdown syntax, the `%` directives and the
  editor's own features. The editor and the admin bar now come in English and French —
  `installContentSite { language = "fr" }`, `auto` (the default) following the browser.
- `Storage`: content, settings and records, namespaced per plugin; `MemoryStorage` and `FileStorage` (plain
  files: `content/`, `settings/<ns>.properties`, `records/<ns>/<collection>/<id>.json`).
- `IdentityProvider` (roles, optional password `authenticate`), `Roles` (patterns per role — `admin: *`,
  `editor: content.*` — its `can` open for target-scoped rights), `MemoryIdentityProvider`, and `loginPage`:
  `POST /login` and `/logout` over the provider.
- Plugins: `Plugin` (`id`, declared `settings`, `install(site)`) registering on `Site` — tools and block tools,
  routes, interceptors run before routing, head/foot fragments (`$site.head()`, `$site.foot()`), admin-bar
  entries, publish listeners, scheduled jobs, role grants. `Site.pages()` lists every page the site serves.
- The admin bar, left of every page for `site.admin`: pages, journal, media, plugins and their settings,
  roles, and the plugins' entries — a link, a `{columns, rows}` table, or another application framed (a
  webmail); `/api/site/*` behind it, `admin.js`/`admin.css` in front.
- `Setting` is made by the function naming its kind — `Setting.text`, `.textarea`, `.number`, `.boolean`,
  `.secret`, `.choice(key, choices = …)` — with an optional `group` the admin form gathers it under. An
  `AdminEntry` holds `tables` (`AdminTable(id, label, url)`), shown one under the other.
- Media, a fourth `Storage` kind: uploads told by their bytes (PNG, JPEG, GIF, WebP, AVIF, PDF — no SVG),
  named once and served immutable under `/media/`; `MemoryStorage` keeps them within a byte budget (100 MB) and
  refuses past it. The editor uploads a picked, pasted or dropped file and writes it in as markdown.
- Themes: a page `#define`s its regions (`$content`, `$aside`, `$hero`) and ends with `#layout(name)`; the
  active theme's layout renders them where it places them, straight to the response. `Theme` (a plugin naming
  its layouts, under `themes/<id>/layouts/`), `$nav` (`NavItem`s, `here`, `trail`), `$theme` (its settings),
  a themes panel in the admin bar (switch live, `?theme=` preview for admins), and `BasicTheme` (pico),
  which a site without a theme wears.
- `Site.requestTool(name) { call -> }`: `$name`, one value per request — what a page sets and a fragment reads
  back in the same render.
- `Site.notFound { call -> }`: a request nothing answered, before it becomes a 404 — to log it or still
  answer it (a phase of its own ahead of `Fallback`, where the engine answers first).
- `Site.mailer`: how the site sends mail, read at send time.
- The demo runs dockerized (`kroom-webapp-authoring/demo/compose.yml`): the repository's wrapper in a JDK
  container, Mailpit beside it.

#### kroom-webapp-velocity
- `pages()` serves `/` as `pages/index.html`, and a folder's `index` likewise (`/docs` → `pages/docs/index.html`).
  `resolvePage` resolves the same, so a block of the home page is previewed within `/`; the authoring plugin's
  `pageResolver` lets a site answer for pages that are not templates (the authored ones).

#### kroom-webapp-core
- `Mailer` moves here from kroom-webapp-auth (which keeps the name as an alias), so any module can send
  through the application's transport.

#### kroom-webapp-authoring-demo
- The demo is a module of its own (`kroom-webapp-authoring/demo/`, `./gradlew :kroom-webapp-authoring-demo:run`), no longer the
  authoring tests': its own pages (home, contact, a topic per placeholder, login) and four ways in — admin,
  editor, an author who edits the topics only (a `Roles` subclass scoping `content.edit`), a visitor. The
  `DummyTheme` moves to kroom-webapp-authoring's test fixtures, for its tests and the demo.
- `$stack`, a block tool: the libraries the site stands on (`$stack.projects`, `$stack.byName`), listed by a
  home-page block — typed data for the editor's completion to unfold. A topic page turns its path segment into a
  `Topic` in its header (`##@ subject: …Topic = $stack.topic($topic)`), the pattern a real site uses.

#### kroom-plugin-webmaster, -forms
- Example plugins under `kroom-webapp-authoring/plugins/`, one artifact each, all in the demo. A `DummyTheme` —
  every layout of the theme contract (`default`, `article`, `sidebar`, `landing`) and both regions, in a look
  nobody mistakes — lives in kroom-webapp-authoring's tests, for testing themes end to end.
- webmaster: the health of the site's URLs — meta and Open Graph tags (a page's own through `$seo.title(…)`,
  `$seo.description(…)`, `$seo.image(…)`, `$seo.noindex()`), robots.txt and sitemap.xml; redirect rules with
  `{name}` captures in the path and the query (values encoded) and application resolvers (`@player`), the 404s
  counted; every page walked on a schedule, every link and picture tried, the broken ones listed with their
  page. One public URL, grouped settings, one admin entry with three tables.
- forms mails each message to its `notify` address when the site has a mailer.

#### kroom-webapp-velocity
- `pages()` leaves a path no template backs unanswered instead of answering 404 itself, so an application's
  fallbacks see it; the client still gets a 404.
- `pageCatalog()`: every page template and the route it is served at.
- `privateSegments` (default `inc`): directory names never served as pages — `pages/inc/header.html` is a
  partial, no longer answered at `/inc/header`; one rule for `pages()`, `placeholderPages()`, `resolvePage`
  and the catalog. `installContentSite { privateSegments = … }` passes it through.

### Fixed

#### kroom-common — a keyed item that goes is marked
- `JsonView.Keyed.remove(id)` told the root and the items above it, not the id that left: in a browser's targeted
  merge an item of another region under that same id (who is here, reading a call keyed by user) stayed clean and
  showed what was no longer true. The removed item is marked as an added one is (`State.dirty`). Pinned in
  `LogicTest`.

#### kroom-webapp-l10n — a translator leaves nothing behind
- A page translated at run time and the fragments it `#translate`s shared one html reader, so a fragment was read
  from wherever its page had stopped: after a page ending in an open `<pre>`, a tag or a `<script>`, a fragment's
  text stayed untranslated, and was cached so. Each template is read on its own.
- `respondVelocityTranslated` left its `Translator` on the thread (`Translator.current`), where a `#translate` of
  a later request served by that thread could find it: it is there for the merge only.
- Pinned in `TranslatorStateTest`.

#### kroom-webapp-core, kroom-server — a stream its client closed is no error
- `installCore`'s catch-all took what ktor answers by itself: since `cancelCallOnClose`, every stream a browser closed
  was logged `ERROR Unhandled exception` with the cancellation's trace, and a `BadRequestException` (a
  `call.receive<T>()`, a `getOrFail`) answered 500. It now leaves ktor a cancelled call (its line, at debug) and every
  exception it has a status for (400, 404, 413, 415, 504). Pinned in `InternalErrorTest`, the closed call over a real
  Netty socket.
- `room(…)`'s stream logs its one line (`Stream of '…' closed by the client in room '…'`) when the close cancels
  the call too, not only when a write fails.
- A room stopped from outside its loop (`room.stop()` at shutdown, in a test) no longer logs `ERROR Error in room '…'`
  with a `ClosedReceiveChannelException`: the loop ends on its closed queue.

#### kroom-webapp-assets, kroom-webapp-authoring — a snapshot's scripts were the browser's for an hour
- `KroomAssets.VERSION` (and `AuthoringAssets.VERSION`, `$site.version`) of a `-SNAPSHOT` build also says when the
  run started: its files change from one run to the next under one version, and `?v=…-SNAPSHOT` with
  `max-age=3600` kept a browser on the script of the run before. A released version is unchanged. No longer `const`.

#### kroom-webapp-velocity — the folder of a placeholder page
- `resolvePage("/topics")` answered `pages/topics/_topic_.html` with `index` for a topic, while the route serves a
  404 there: the folder form is an `index` template's alone. On an authoring site the pages panel offered to
  create that section's page and the server refused it ("a page already answers /topics"). Pinned in
  `PlaceholderPagesTest` and `PagesTest`.

#### kroom-server — a room that stops
- A stopping `ChatRoom` lost the lines still queued for its store: the writer ran on the room's scope. It now runs on
  its own, and the stopping room closes its channel: the pending lines are written, then it ends.
- A room closing when empty ignored anonymous spectators and stopped under them; spectators now count.

#### kroom-webapp-l10n
- `HtmlFragmentTranslator` reads HTML through kroom-common's `Segmentizer` instead of two regexes: a
  tag with several translatable attributes (`title` and `aria-label`) had only the last one
  translated; a text run between two references was a phrase or not depending on where the tags
  happened to fall. A phrase now needs a letter (a lone dash, emoji or number is no token to
  translate). `TemplateTranslator` parses with informal navigation on: with the pure 3.0 config,
  `$site.name` is `$site` + the text `.name`, which the reader then offered for translation. Every
  template of the repo translates byte-identically; the missing-token report loses its artefacts.
- A skip prefix names a segment: `/events/` also spares `/events` itself (the bare path was
  redirected to `/en/events`, which no `EventSource` follows usefully).

#### kroom-examples/chifoumi
- Runs again: serializable session cookie, `install(SSE)`, the assets module on the classpath (its
  pages loaded `/js/domhelper.js` from nowhere), the scripting engine for its interpreted templates,
  `#versioned` script tags over a version cache, dev-mode static serving from the sources.

#### kroom-webapp-assets
- domhelper's synthetic `click()` now bubbles (delegated handlers never saw it); `sse.off(name)`
  keeps the EventSource listener bound once (a later `on` doubled every event).

#### kroom-webapp-authoring
- An authored page's editable default for its header, west and footer was kroom's generic region by literal
  path, so a Skin's rewritten `*_default` never reached the block; the defaults now resolve through the theme
  (`$site.region("header_default")`), in `kroom/page.html` and kroom's own regions alike (reported from site2026).

#### kroom-webapp-core
- `installCore`'s 404 handler replaced every 404 body with plain `Not Found` — an API's JSON error included,
  so the editor never received `noPage`, `noHistory` or `noSuchRevision` (reported from site2026). Only a 404
  without a body gets one now.

#### kroom-webapp-authoring
- The admin bar spoke English whatever the application said: its words sat in admin.js, out of reach of the
  editor's table. One table now reaches both, and a plugin's labels are translatable by key (reported from
  site2026).

#### kroom-markdown
- A block is its page's: its path derives from the template the merge started from, not the one rendering
  now — a region a page `#define`s and its layout renders found the layout's blocks instead.

#### kroom-webapp-velocity
- A partial beside the pages (`header.inc.html`) was listed by the template catalog — and, under a
  placeholder directory, mounted as a route (`/city/{name}/header.inc`). The catalog now keeps only what
  `servePage` would serve.

## [0.23-kmp-velocity-05]

### Fixed

#### kroom-webapp-assets
- Dated releases carried `KROOM_VERSION = "…-SNAPSHOT"`, so every `?v=` asset link (and its one-year
  cache) stayed the same across upgrades: `generateVersion` declared no input and stayed UP-TO-DATE
  when the publish script changed the version. The version is now a task input.

#### kroom-webapp-velocity
- The `TranslateDirective` auto-registration probe asked the wrong classloader. It used
  `Class.forName`, resolving against the plugin class's own loader, while the engine resolves a
  `runtime.custom_directives` entry through the thread-context loader and then its own — and a name
  the engine cannot find is now a fatal `VelocityException` at `init()` rather than a silent skip.
  The probe now goes through the engine's `ClassUtils`, so it asks exactly the question the engine
  will answer.

### Added

#### kroom-markdown
- New ktor-free module: markdown content blocks written in a `%` dialect of Velocity (`#` is a markdown
  heading), merged then converted to HTML by flexmark (GFM tables, strikethrough, autolink, task lists).
  The `%` lexer is generated at build time from the engine's grammar template. Built on velocity 3.0
  alone — no classic facade: `#markdown` is a native macro (`MarkdownMacro`), registered with
  `addMacro("markdown", …)` on whichever page engine the application runs, a 3.0 `VelocityEngine` or the
  classic facade; the block renders on its own always-interpreted 3.0 engine, configured by
  `MarkdownConfig` (or `markdown.*` properties: `loader`, `acl`, `sandbox`, `tools`, `block.wrapper`,
  `missing`, `broken`, plus any velocity 3.0 key). A block does not inherit the page's context: it sees what the page
  passes, `#markdown("description", {"club": $club})`, plus the tools named in `markdown.tools`. Blocks are
  user-authored, so they are watched by default: strict mode (including the header contract), the sandbox
  — whose capability rule (velocity `-20260923-01`) means a block derives from what it is handed and
  conjures nothing: no constructors, no statics, no reflection — plus `- write *`, and of VTL's 2.x conveniences only informal navigation (prose writes
  `$club.name` unbraced). A stored block that fails to render is logged and shows `broken` in its place —
  published content never takes the page down; a draft (the editor's `kroomDrafts`) fails loudly instead,
  checked by `validate(source, declared)` against the arguments the page passes — the roots it reads that
  nobody provides, even in a branch this render skips — then rendered (`BlockException`, positioned).

#### kroom-webapp-authoring
- New module: block-based content editing over a `ResourceStore` — a velocity `ResourceLoader` that also
  writes, so the bytes a visitor's page renders are the ones a submit wrote, with no publication step.
  Concurrency is one lock per block with a timeout read at access (no scheduler, no SSE, no room); a submit
  carries the rev it started from and is answered with theirs rather than overwriting — and, with velocity
  installed, the page it sits in: the page is rendered with the body as the preview does, and a body that
  would break it is refused (422, the author's problem positioned). `Versioned` stores
  (`VersionedMemoryResourceStore`, an application's git-backed one) add history and a site-wide journal.
  The editor ships with it: a default block wrapper, `authoring.js` (textarea in place, diff on conflict and
  in history), and `installContentSite`, which puts both template stacks, the edit API and placeholder page
  routing in one call. `./gradlew :kroom-webapp-authoring:demo` runs it.

#### kroom-common
- `PathTemplate`: the one place a `_joker_` path is matched (`/club/13Ma` → `code = 13Ma`) and expanded
  back. Page routing and block inclusion share it, so a page and its blocks cannot disagree.

#### kroom-webapp-velocity
- `placeholderPages()` mounts a parameterized page template as the route it describes
  (`pages/club/_code_.html` → `/club/{code}`), a concrete page still winning over the placeholder.
- `VelocityConfig.properties`: an open door for engine properties (`markdown.*` among them).
- `VelocityPlugin` registers the `#markdown` macro when `kroom-markdown` is on the classpath, configured
  from its `markdown.*` properties (same engine-side classpath probe as `TranslateDirective`).
- `PortableTemplatesTest` pins that everything kroom ships — the `kroom-macros.vtl` library and the
  page templates — renders under a pure 3.0 `Config`, with `compat.informal_navigation`,
  `duck_typing`, `elvis_falsy`, `string_escapes` and `introspection` all off. `VelocityPlugin` runs
  the classic facade, where those are on; the build-time pipelines start from a config where they are
  off, and the difference is *silent* (informal `$a.b` in free text renders the root followed by a
  literal `.b`). kroom's own templates are therefore formal-only; a consumer's templates stay the
  consumer's call, configured on their side.

### Changed

#### kroom-webapp-authoring
- `ContentSiteConfig.devDir` is the source resources directory (`src/main/resources`), no longer its
  `templates/`: layouts are read from `<devDir>/<templatePath>` and static files from `<devDir>/static`,
  so a stylesheet edit shows without a restart, as a layout edit already did.

- The editor opens a block in three tabs — **markdown** (with formatting buttons: bold, italic, heading,
  link, lists, quote, code; Ctrl+B/I/K), **preview** (rendered when shown, replacing the fold beside the
  textarea) and **history** (was a ⟲ button and a dialog; a revision now diffs against what you are
  writing). Before editing, an author sees only ✎.
- Every word of the editor is in one overridable `strings` table (`kroomAuthoring.strings` client side,
  `AuthoringConfig.strings` / `ContentSiteConfig.strings` server side), edit API error codes included; the
  default wrapper carries no text. `AuthoringAssets` is a class carrying those strings (`VERSION` in its
  companion).
- Pictograms: one stroked SVG path per button on a 24px grid, overridable (`kroomAuthoring.icons`), sized and
  stroked by CSS variables; edit, submit and cancel are coloured (blue, green, red — variables too), raised,
  and pressed when clicked. The default wrapper's edit button is empty: authoring.js draws and names it.
- Drafts: what is typed is kept in `localStorage` per author and block, with the rev it started from, and
  dropped on submit or cancel; a reload mid-edit (the lock still ours) reopens the block as it was, an older
  draft heads the block's history. The leave-page warning remains only where storage fails. The wrapper
  carries `data-user`.
- While a block is edited, no block shows its edit handle.

#### kroom-webapp-core
- Dev-mode static routes answer `Cache-Control: no-cache`, classpath fallback included (was one hour).
- `respondError(message, status, code, args)`: an optional `code` and `args` beside the English message, for
  a client that translates. The edit API answers one for every error.

### Build
- velocity `3.0.0-BETA-20260924-01` (header defaults, header mode, strict header contract, full sandbox,
  write ACL, kotlin default arguments and extension functions); antlr-kotlin 1.0.10, matching the
  engine's runtime. A kotlin default argument called reflectively needs kotlin-reflect at runtime (it
  comes with velocity-engine-scripting); without it the call throws, naming the fix.
- The build version is now `0.23-kmp-velocity-SNAPSHOT`; maven-local pre-releases are dated from here on,
  `0.23-kmp-velocity-<yyyymmdd>-<nn>` (same scheme as the velocity betas), tagged at the built commit.
- velocity `3.0.0-BETA-20260901-01` → `3.0.0-BETA-20260916-01`. Two months of engine work (classic
  `Uberspect`/event-handler/conversion-handler facades, codegen convergence, compat flags, resource-name
  normalization) land without a source change here: compile, tests and rendering are unaffected.
  `RuntimeConstants.VM_LIBRARY` is `velocimacro.library.path`, so kroom's programmatic configuration
  uses only canonical keys — verified against the engine's new init-time report of settings it ignores,
  which names none for either the dev or the production loader shape.

## [0.23-kmp-velocity-04]

### Fixed

#### kroom-webapp-velocity
- Production template loading: `templatePath` was a no-op — velocity's `ClasspathResourceLoader` had
  no path property, so bare template names never resolved under `templates/` in a packaged jar (only
  the dev-mode file loader worked). Fixed at source in the engine (`ClasspathResourceLoader.path`) and
  wired here as two loaders: pages by bare name under `templatePath`, macro library at classpath root.
- Engine properties use the canonical 2.x names (`resource.loaders`,
  `resource.loader.<name>.class/.path/.cache/.modification_check_interval`). The engine's
  deprecated-key translator is gone, so the old positional spelling was silently inert —
  `modification_check_interval` in particular was being dropped, defeating dev-mode hot reload.

#### kroom-webapp-l10n
- A character reference adjacent to text was not translated: `&#x25B6;` lexes as a macro call named
  `x25B6` (undefined, so it renders back verbatim — but it splits the text node), leaving `; Play`
  and `&` as fragments and no `&#x25B6; Play` token to look up. A *leading* character reference — or
  the bare `;` residue of one the parser cut at its `#` — is now decoration: out of the lookup key,
  back into the output verbatim. Both `&#x25B6; Play` and `&#182; Play` key on `Play`, and a lone
  reference (`<span>&#x25B6;</span>`, the common case) yields no token at all instead of a spurious
  `&` reported missing. Trailing punctuation is untouched — most keys legitimately end on it.
- `TemplateTranslator`: `#include` takes several targets (`ASTInclude.targets`); each literal one is
  rewritten, as `#parse`'s single target already was.

### Build
- velocity `3.0.0-BETA-20260703-02` → `3.0.0-BETA-20260901-01`. Brings the `ClasspathResourceLoader`
  path property and the classic `Directive`/`Parse` facade on the 3.0 render pipeline, so
  `TranslateDirective` and `Translator` survive the engine's guts cleanup unchanged and
  `runtime.custom_directives` is honoured again.
- `velocity-engine-scripting` added as `testRuntimeOnly` to the velocity and l10n webapp modules:
  tests render loader-served (interpreted) templates, which now need the runtime compiler. Production
  renders compiled classes, so scripting stays droppable there.

## [0.23-kmp-velocity-01]

Tagged pre-release on branch `kmp-velocity`, published to maven-local only.

### Added

#### kroom-webapp-l10n
- `TemplateTranslator`: build-time, source-to-source template translation. Parses a template with
  the velocity 3.0 common front-end and splices a translated variant of each text node back into the
  source by its AST `range`, leaving every directive/`$ref`/byte of structure intact — the basis for
  per-language, build-time-compiled templates (no runtime translation). Shares the HTML text logic
  with the runtime `Translator` via the extracted `HtmlFragmentTranslator`, which now also translates
  `title`/`alt`/`aria-label` attribute values (previously missed). Literal `#parse`/`#include` targets
  are rewritten (e.g. language-prefixed) so relocated per-language trees still resolve; dynamic targets
  are left untouched.

#### kroom-velocity-l10n (new — Gradle plugin)
- `com.republicate.kroom.velocity-l10n`: emits per-language, build-time-compiled templates. For each
  template × language it writes a translated `.vm` (via `TemplateTranslator`) and a
  `context(request, session, app) … = vtlFile(…)` stub, plus a `renderPage(path, lang)` dispatcher;
  points the velocity `templateRoot` at the generated trees and wires the stubs into the consumer's
  main source set. Refs resolve to declared, typed scope members (compile-checked, multiplatform-ready);
  no runtime translation. Configured via a `velocityL10n { }` DSL (`=` assignment, Gradle 8.4+).
- Base scope interfaces consumers extend so kroom's own members resolve without re-declaring them:
  `KroomAppScope { versions }` (velocity) and `KroomL10nRequestScope { lang; languages; jsTranslations }` (l10n).
- Macro-library support: `kroom-macros.vtl` (`#versioned`, …) is dropped into the template root and
  registered via velocity's `macroLibrary`, so library macros (and literal macro args) resolve in the
  compiled path. Extra libraries via `velocityL10n { macroLibraries = listOf(...) }`. (velocity -07
  brings `##`/whitespace-gobbling parity between the compiled and runtime paths.)
- The plugin injects `velocity-engine-common` (at the exact version it was built against) into the
  consumer's `implementation` — the generated stubs need it at compile time, and a hand-added copy
  would be a version-drift trap. A consumer needs only the plugin id and the `velocityL10n { }` block.

### Changed

#### kroom-view, kroom-webapp-velocity, kroom-webapp-l10n
- Consume the new KMP `velocity-engine-core` 3.0 (`3.0.0-BETA-20260703-01`) in place of
  2.4.1. `velocity-tools-generic:3.1` kept — binary-compatible; its transitive engine 2.3
  is evicted. `TranslateDirective` adapted to the engine's idiomatic Kotlin directive API
  (`override val name`, non-null `getTemplate`).

### Build
- `mavenLocal()` added (last) to repositories so the velocity 3.0 BETA resolves. Until
  velocity 3.0 reaches Central, a fresh checkout needs it in maven-local.
- Kotlin `2.3.0` → `2.4.0` (context parameters are stable, no flag — needed for the typed
  `vtlFile` build-time template path). atomicfu `0.29.0` → `0.33.0` (0.29's legacy bytecode
  transformer can't analyze 2.4 output; 0.33 uses the IR transform).

## [0.23]

### Added

#### kroom-webapp-assets
- domhelper: `.first()`, `.last()`, `.has(selector)`. `first`/`last` reduce a set to
  its first/last element (a singleton returns itself); `has` keeps elements with a
  descendant matching `selector`, returning a `NodeList`. All three are no-match safe
  (return the empty proxy).

## [0.22] - 2026-06-19

### Added

#### kroom-webapp-velocity
- `VelocityConfig.pageRenderer` — set the `pages()` render strategy declaratively in
  `installVelocity { pageRenderer = … }`, for symmetry with `application()/session()/
  request()`. Equivalent to the existing post-install `velocity.pageRenderer = …`;
  null keeps the default `respondVelocity`. l10n still overrides it on install.

#### kroom-webapp-assets
- `api.post/put/delete` accept a `FormData` body for file uploads: it's sent as-is so
  the browser sets the `multipart/form-data` boundary, with `Content-Type` dropped but
  `Accept`/`Authorization` kept. Non-`FormData` bodies are still JSON as before. The
  three verbs now share one `send()` helper (was triplicated).

## [0.21] - 2026-06-19

### Added

#### kroom-webapp-velocity
- `ApplicationCall.servePage(path, prefix, extension): Boolean` — the lookup behind
  `pages()`, exposed as a callable. Since ktor scores a root param route (`/{x}`)
  above the `pages()` tailcard, an app with such a route delegates its no-match
  branch to `servePage` (renders if a template backs the path, else returns false to
  fall through) instead of reimplementing the resolve/sanitise/exists logic.

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
- `Route.pages(prefix = "pages", extension = "html")` — a convention that renders a
  clean URI as a template (`/source` → `pages/source.html`, `/legal/terms` →
  `pages/legal/terms.html`): a content page is *just a template*, no route or model.
  Mount it **last**, after specific/param routes; it 404s paths with no backing
  template. Traversal, dotfiles and partials (`header.inc`, the macro library) never
  resolve. Renders through the scope chain (`$user` etc. apply), via a new
  `VelocityPlugin.pageRenderer` hook (default `respondVelocity`), overridable so
  other modules can change the strategy.

#### kroom-webapp-l10n
- `$lang`, `$languages`, `$jsTranslations` are registered as request-scope values,
  so they are available on every render (not only the translated path), and
  `respondVelocityTranslated` now renders through the same scope chain — a
  translated page sees the full base context (`$user`, …) and still translates.
- On install, overrides `pageRenderer` to `respondVelocityTranslated` — so `pages()`
  content pages translate automatically when l10n is present.

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
