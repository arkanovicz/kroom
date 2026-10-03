#!/bin/sh
# {{name}}: build and run it — ./run.sh, or ./run.sh dev for Mailpit beside it (http://localhost:8025).
cd "$(dirname "$0")" || exit 1
if [ "$1" = dev ]; then shift; set -- --profile dev "$@"; fi
exec docker compose "$@" up
