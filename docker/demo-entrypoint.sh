#!/usr/bin/env sh
# docker/demo-entrypoint.sh: the container entrypoint of the DEMO deployment only (task 9, D24).
#
# Two processes, never one:
#   1. the refresh loop: `demo-refresh --every 600s`, with the demo operator login (DEMO_DB_USER) and the application login it seeds
#      with (DB_USER). It retires ended leases and tops the pool up. One pass at a time, by the database's refresh lock.
#   2. the web process: app.demo.enabled=true. It never holds the demo operator login: DEMO_DB_* are removed from its environment.
#
# The database must have been made a demo deployment once, on an empty database: `java -jar app.jar demo-init`.
# A regular deployment does not use this script.
#
# Requirement 7. Task 9 (design stop, section 7).

set -eu

: "${DB_URL:?DB_URL must be set}"
: "${DEMO_DB_USER:?DEMO_DB_USER must be set (the demo operator login)}"
: "${DEMO_DB_PASSWORD:?DEMO_DB_PASSWORD must be set}"
JAR="${APP_JAR:-/app/app.jar}"
EVERY="${DEMO_REFRESH_EVERY:-600s}"
POOL="${DEMO_POOL:-30}"

# 1. The refresh loop, in the background, restarted if it ever exits.
(
    while true; do
        java -jar "$JAR" demo-refresh --pool "$POOL" --every "$EVERY" || true
        sleep 30
    done
) &
REFRESH_PID=$!

# 2. The web process, without the demo operator's credentials.
env -u DEMO_DB_USER -u DEMO_DB_PASSWORD java -jar "$JAR" --app.demo.enabled=true &
WEB_PID=$!

stop() {
    kill "$WEB_PID" "$REFRESH_PID" 2>/dev/null || true
}
trap stop INT TERM

# The container lives as long as the web process does.
wait "$WEB_PID"
STATUS=$?
stop
exit "$STATUS"
