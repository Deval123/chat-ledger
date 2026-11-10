#!/bin/sh
# deploy/check-image.sh: checks a built chat-ledger image before it is scanned or pushed (task 13, D1). Run by CI's image-build job.
#
#   sh deploy/check-image.sh chat-ledger:test
#
# Refuses an image that: runs as root or as another uid than 65532; has a shell; has a code jar the process could change; has another
# entrypoint; lacks the revision label; or whose build history names a password, secret or token (a build argument or a secret mount
# would show there). Exit 1 on any failure.
set -u
IMAGE="${1:?Usage: check-image.sh <image>}"
FAILURES=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAILURES=$((FAILURES + 1)); }

user="$(docker image inspect -f '{{.Config.User}}' "$IMAGE")"
if [ "$user" = "65532:65532" ]; then pass "runs as 65532:65532"; else fail "runs as [$user]"; fi

entrypoint="$(docker image inspect -f '{{json .Config.Entrypoint}}' "$IMAGE")"
if [ "$entrypoint" = '["java","-jar","/app/app.jar"]' ]; then pass "entrypoint is the jar"; else fail "entrypoint is $entrypoint"; fi

revision="$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$IMAGE")"
if [ -n "$revision" ] && [ "$revision" != unknown ]; then pass "revision label $revision"; else fail "no revision label"; fi

if docker image history --no-trunc --format '{{.CreatedBy}}' "$IMAGE" | grep -qiE 'password|secret|token'; then
    fail "the build history names a password, secret or token"
else
    pass "the build history names no password, secret or token"
fi

# No shell: a distroless runtime. (The run must fail to start /bin/sh.)
if docker run --rm --network none --entrypoint /bin/sh "$IMAGE" -c true >/dev/null 2>&1; then
    fail "the image has a shell"
else
    pass "no shell in the image"
fi

# The code is root's and read-only for the process; /app is not writable by it. Read from the image's files, not by running it.
container="$(docker create "$IMAGE")"
listing="$(docker export "$container" | tar --numeric-owner -tvf - 2>/dev/null | grep -E '[[:space:]]app/(app\.jar)?$')"
docker rm "$container" >/dev/null
# GNU tar prints the owner as 0/0, busybox and bsdtar as 0 0.
if printf '%s\n' "$listing" | grep -qE '^-r--r--r--[[:space:]]+0[/[:space:]]+0[[:space:]].*app/app\.jar$'; then pass "app.jar is root-owned, 0444"; else fail "app.jar: $listing"; fi
if printf '%s\n' "$listing" | grep -qE '^dr[w-]xr-xr-x[[:space:]]+0[/[:space:]]+0[[:space:]].*app/$'; then pass "/app is root-owned, not writable by others"; else fail "/app: $listing"; fi

# The two commands the compose file relies on are dispatched before Spring starts: the health probe with nothing listening prints
# exactly DOWN and exits 1; db-migrate with no configuration exits 2 with its usage. (A Spring start would print logs instead.)
HARDENED="--rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp:size=16m"
# shellcheck disable=SC2086 # the flags are one word each
probe_out="$(docker run $HARDENED --entrypoint java "$IMAGE" -Xmx16m -XX:TieredStopAtLevel=1 -jar /app/app.jar health-probe 2>/dev/null)"
probe=$?
if [ "$probe" -eq 1 ] && [ "$probe_out" = DOWN ]; then pass "health-probe runs and reports DOWN with nothing listening"
else fail "health-probe exited $probe and printed [$(printf '%s' "$probe_out" | head -c 80)]"; fi
# shellcheck disable=SC2086
migrate_err="$(docker run $HARDENED "$IMAGE" db-migrate 2>&1 >/dev/null)"
migrate=$?
if [ "$migrate" -eq 2 ] && printf '%s' "$migrate_err" | grep -q '^Usage: db-migrate'; then pass "db-migrate runs before Spring and refuses an empty configuration"
else fail "db-migrate exited $migrate"; fi

echo "Failures: $FAILURES"
[ "$FAILURES" -eq 0 ]
