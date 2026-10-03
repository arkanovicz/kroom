#!/bin/sh
# A kroom site, from a machine with Docker and nothing else:
#
#   sh -c "$(curl -fsSL https://republicate.com/kroom/create.sh)"
#
# (that form keeps your terminal as the standard input: the starter asks questions). It fetches the latest
# starter jar from republicate.com/maven2 and runs it in a bare JRE container, as you, in the current directory.
set -e
REPO=https://republicate.com/maven2/com/republicate/kroom/kroom-webapp-authoring-starter
version=$(curl -fsSL "$REPO/maven-metadata.xml" | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p' | head -1)
[ -n "$version" ] || version=$(curl -fsSL "$REPO/maven-metadata.xml" | sed -n 's:.*<version>\(.*\)</version>.*:\1:p' | tail -1)
[ -n "$version" ] || { echo "create.sh: no starter published at $REPO" >&2; exit 1; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "kroom starter $version"
curl -fsSL -o "$tmp/starter.jar" "$REPO/$version/kroom-webapp-authoring-starter-$version-all.jar"
docker run --rm -it -u "$(id -u):$(id -g)" -v "$PWD":/work -v "$tmp/starter.jar":/starter.jar:ro -w /work \
    eclipse-temurin:21-jre java -jar /starter.jar /work
