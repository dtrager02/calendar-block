#!/bin/sh
# Cron wrapper. Cron's PATH is minimal, so set JAVA to an absolute path if java isn't in /usr/bin.
set -eu
JAVA="${JAVA:-/usr/bin/java}"
DIR="$(cd "$(dirname "$0")" && pwd)"
echo "[$(date)] calendar-blocker run"
exec "$JAVA" -jar "$DIR/out/blocker/assembly.dest/out.jar" run "$@"
