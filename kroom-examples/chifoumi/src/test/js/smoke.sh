#!/bin/sh
# The chifoumi pages over a recorded match (the golden in chifoumi-common/src/commonTest/resources, refreshed by LobbyTest);
# node lives in docker here (house rule: no node on the host), jsdom installed beside the stock scripts' tests.
cd "$(dirname "$0")" || exit 1
exec docker run --rm -u "$(id -u):$(id -g)" -v "$PWD/../../../../..":/home/app -w /home/app/kroom-webapp-assets/src/test/js node:24 \
    /bin/bash -c "npm install --silent --no-fund --no-audit --no-progress jsdom >/dev/null 2>&1 && node /home/app/kroom-examples/chifoumi/src/test/js/pages.mjs"
