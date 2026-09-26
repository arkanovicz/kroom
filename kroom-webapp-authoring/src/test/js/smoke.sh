#!/bin/sh
# The editor's and the admin bar's smoke tests. node lives in docker here (house rule: no node on the host).
cd "$(dirname "$0")" || exit 1
exec docker run --rm -u "$(id -u):$(id -g)" -v "$PWD/../../../..":/home/app -w /home/app/kroom-webapp-authoring/src/test/js node:24 \
    /bin/bash -c "npm install --silent --no-fund --no-audit --no-progress jsdom >/dev/null 2>&1 && node smoke.mjs && node admin.mjs"
