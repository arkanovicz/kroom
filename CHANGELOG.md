# Changelog

All notable changes to kroom will be documented in this file.

## [Unreleased]

### Changed (breaking)

#### kroom-webapp-authoring
- `installContentSite { store = … }` is now `storage = …` (a `Storage`; its `content` is the former store),
  and `canEdit = { session, path -> … }` is gone: `identity = IdentityProvider { session -> roles }` answers
  roles, `roles` (a `Roles` table) says what each may do, and editing asks `content.edit` on the block's path.
  `installAuthoring` takes `identity`/`roles` likewise. The default provider knows nobody: nothing is editable
  until one is given (it used to be any session).

### Added

#### kroom-webapp-authoring
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
- `Site.requestTool(name) { call -> }`: `$name`, one value per request — what a page sets and a fragment reads
  back in the same render.
- `Site.notFound { call -> }`: a request nothing answered, before it becomes a 404 — to log it or still
  answer it (a phase of its own ahead of `Fallback`, where the engine answers first).
- `Site.mailer`: how the site sends mail, set by a mail plugin, read at send time.
- The demo runs dockerized (`kroom-webapp-authoring/demo/compose.yml`): the repository's wrapper in a JDK
  container, Mailpit beside it, framed in the admin bar.

#### kroom-webapp-core
- `Mailer` moves here from kroom-webapp-auth (which keeps the name as an alias), so any module can send
  through the application's transport.

#### kroom-plugin-webmaster, -forms, -analytics, -webhook, -mail
- Example plugins under `kroom-webapp-authoring/plugins/`, one artifact each, all in the demo.
- webmaster: the health of the site's URLs — meta and Open Graph tags (a page's own through `$seo.title(…)`,
  `$seo.description(…)`, `$seo.image(…)`, `$seo.noindex()`), robots.txt and sitemap.xml; redirect rules with
  `{name}` captures in the path and the query (values encoded) and application resolvers (`@player`), the 404s
  counted; every page walked on a schedule, every link and picture tried, the broken ones listed with their
  page. One public URL, grouped settings, one admin entry with three tables.
- mail: an SMTP transport on angus-mail (tested against an in-process GreenMail), with a sent-mail log; forms
  mails each message to its `notify` address when the site has a mailer.

#### kroom-webapp-velocity
- `pages()` leaves a path no template backs unanswered instead of answering 404 itself, so an application's
  fallbacks see it; the client still gets a 404.
- `pageCatalog()`: every page template and the route it is served at.
- `privateSegments` (default `inc`): directory names never served as pages — `pages/inc/header.html` is a
  partial, no longer answered at `/inc/header`; one rule for `pages()`, `placeholderPages()`, `resolvePage`
  and the catalog. `installContentSite { privateSegments = … }` passes it through.

### Fixed

#### kroom-webapp-core
- `installCore`'s 404 handler replaced every 404 body with plain `Not Found` — an API's JSON error included,
  so the editor never received `noPage`, `noHistory` or `noSuchRevision` (reported from site2026). Only a 404
  without a body gets one now.

#### kroom-webapp-authoring
- The admin bar spoke English whatever the application said: its words sat in admin.js, out of reach of the
  editor's table. One table now reaches both, and a plugin's labels are translatable by key (reported from
  site2026).

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
