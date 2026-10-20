#!/usr/bin/env bash
# NandaniMusic — one-command launcher
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f target/nandani-music.jar ]; then
  echo "[build] target/nandani-music.jar missing — building..."
  mvn -B -q package -DskipTests
fi

exec java ${JAVA_OPTS:-"-Xms128m -Xmx512m"} -jar target/nandani-music.jar
