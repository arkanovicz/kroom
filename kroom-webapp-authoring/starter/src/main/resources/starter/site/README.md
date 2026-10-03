# {{name}}

A [kroom](https://github.com/arkanovicz/kroom) site: pages by developers, words by editors, edited in place.

## Run

```
docker compose up                 # builds, then http://localhost:{{port}}/
./gradlew run                     # with a JDK 21 at hand; `set -a; . ./.env` first for the same secrets
```

Log in at `/login` as `admin` with the password in `.env` (keep that file out of git; it is ignored). The bar on
the left holds the site's settings, its pages — which are its menu —, the journal, the media, the plugins, the
themes and the roles. Hover any block and the pencil opens it.

## Where things are

- `src/main/resources/templates/pages/` — the developers' pages (`#` templates); `index.html` is `/`.
- `data/` — everything the site keeps: blocks, settings, records, uploads. Back it up, it is the site.
- `src/main/kotlin/{{packagePath}}/Main.kt` — the one call that wires it all, `installContentSite`.
- `gradle/libs.versions.toml` — the versions: kroom and its velocity engine come from republicate.com/maven2.
