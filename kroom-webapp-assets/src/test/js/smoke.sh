#!/bin/sh
# The stock front-end's smoke tests. node lives in docker here (house rule: no node on the host).
# editor.mjs reads the built bundle: ./gradlew :kroom-webapp-assets:processResources first.
cd "$(dirname "$0")" || exit 1
exec docker run --rm -u "$(id -u):$(id -g)" -v "$PWD/../../../..":/home/app -w /home/app/kroom-webapp-assets/src/test/js node:24 \
    /bin/bash -c "npm install --silent --no-fund --no-audit --no-progress jsdom >/dev/null 2>&1 && node intents.mjs && node keys.mjs && node markdown.mjs && node i18n.mjs && node editor.mjs"
