#!/bin/sh
# Cron wrapper: pulls the latest config, then runs. Any failure aborts the run; the next cron run retries.
# Cron's PATH is minimal, so JAVA defaults to an absolute path.
set -eu
JAVA="${JAVA:-/usr/bin/java}"
CONFIG_REPO="${CONFIG_REPO:-$HOME/.config/calendar-blocker-config}"
DIR="$(cd "$(dirname "$0")" && pwd)"
echo "[$(date)] calendar-blocker run"
git -C "$CONFIG_REPO" pull --ff-only --quiet
exec "$JAVA" -jar "$DIR/out/blocker/assembly.dest/out.jar" run --config "$CONFIG_REPO/config.yaml" "$@"
