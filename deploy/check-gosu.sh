#!/bin/sh
# deploy/check-gosu.sh: the evidence that the PostgreSQL image's `gosu` is never executed in this deployment (task 13, register 155).
# Read-only; needs the Docker CLI. Run from the repository root, by the weekly "Pinned digests" workflow and by hand:
#
#   sh deploy/check-gosu.sh            (the image compose.yaml pins)
#
# The scanner finds Go standard-library advisories in /usr/local/bin/gosu of the pinned image. They are accepted only while every
# check below passes, re-run every week; any failure ends the acceptance (scan-runtime.sh then fails on them again):
#   1. compose.yaml starts db as 70:70, read-only, with no capabilities and no-new-privileges;
#   2. in the image, every call of gosu is `exec gosu postgres` directly under `if [ "$(id -u)" = '0' ]; then`, and there are exactly
#      the three known ones (the entrypoint and two helpers); gosu is not setuid and the image has no setuid file at all;
#   3. the image's own entrypoint, run under `bash -x` with the db service's settings, never executes gosu, and the server comes up
#      as uid 70;
#   4. control: the same trace started as root does show gosu, so check 3 can see it when it happens.
# Exit 0 when all pass, 1 otherwise.
set -u
FAILURES=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAILURES=$((FAILURES + 1)); }

# The image compose.yaml pins; an argument names another one (only the tests do, to show each check can fail).
IMAGE="${1:-$(sed -n 's/^    image: \(postgres:[^ ]*@sha256:[0-9a-f]\{64\}\)$/\1/p' deploy/compose.yaml)}"
if [ -z "$IMAGE" ]; then
    echo "No pinned PostgreSQL image in deploy/compose.yaml." >&2
    exit 2
fi
echo "Image: $IMAGE"

# 1. The db service's settings, read from its block of compose.yaml.
db_block="$(awk '/^  db:$/ { on = 1; next } /^  [a-z-]+:$/ { on = 0 } on' deploy/compose.yaml)"
for setting in '    user: "70:70"' '    read_only: true' '    cap_drop: [ALL]' '    security_opt: ["no-new-privileges:true"]'; do
    if printf '%s\n' "$db_block" | grep -qxF "$setting"; then pass "db:$setting"; else fail "db does not have:$setting"; fi
done

# 2. Where gosu is called, and how, in the image's scripts; no setuid file.
calls="$(docker run --rm --network none --entrypoint sh "$IMAGE" -c '
    for f in /usr/local/bin/*.sh; do
        awk -v f="$f" "/gosu/ && !/^[[:space:]]*#/ { print f \":\" NR \":\" prev2 \"|\" prev \"|\" \$0 } { prev2 = prev; prev = \$0 }" "$f"
    done')"
count="$(printf '%s\n' "$calls" | grep -c .)"
# Each record is file:line:<two lines before>|<line before>|<the call>; compared field by field, leading blanks removed.
guarded="$(printf '%s\n' "$calls" | awk -F'|' '
    { sub(/^[^:]*:[0-9]+:/, "", $1); for (i = 1; i <= 3; i++) gsub(/^[ \t]+/, "", $i) }
    $1 == "if [ \"$(id -u)\" = '\''0'\'' ]; then" && $2 == "# then restart script as postgres user" && $3 == "exec gosu postgres \"$BASH_SOURCE\" \"$@\"" { n++ }
    END { print n + 0 }')"
if [ "$count" -eq 3 ] && [ "$guarded" -eq 3 ]; then
    pass "the 3 calls of gosu each run only under: if [ \"\$(id -u)\" = '0' ]"
else
    fail "gosu calls: $count found, $guarded under the root-only condition (expected 3 and 3): $calls"
fi
setuid="$(docker run --rm --network none --entrypoint sh "$IMAGE" -c 'stat -c %a /usr/local/bin/gosu; find / -xdev -perm -4000 -type f 2>/dev/null')"
if [ "$setuid" = 755 ]; then pass "gosu is 0755, not setuid; the image has no setuid file"; else fail "setuid check: $setuid"; fi

# 3 and 4. The entrypoint traced as the db service runs it, and as root.
trace() { # trace <extra docker run flags...>: the entrypoint's bash -x trace until the server is ready (at most 60 s)
    name="chat-ledger-gosu-check-$$"
    # shellcheck disable=SC2068 # one flag per word
    docker run -d --name "$name" --network none $@ --tmpfs /var/run/postgresql:size=1m --tmpfs /tmp:size=16m \
        --tmpfs /var/lib/postgresql/data:size=64m -e PGDATA=/var/lib/postgresql/data/pgdata -e POSTGRES_HOST_AUTH_METHOD=trust \
        --entrypoint bash "$IMAGE" -x /usr/local/bin/docker-entrypoint.sh postgres >/dev/null
    i=0
    while [ "$i" -lt 60 ] && [ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null)" = true ] \
        && ! docker logs "$name" 2>&1 | grep -q 'database system is ready to accept connections'; do
        i=$((i + 1)); sleep 1
    done
    docker logs "$name" 2>&1
    docker exec "$name" sh -c 'echo "PID1-UID $(awk "/^Uid:/ { print \$2 }" /proc/1/status)"' 2>/dev/null
    docker rm -f "$name" >/dev/null 2>&1
}
ours="$(trace --user 70:70 --read-only --cap-drop ALL --security-opt no-new-privileges)"
if printf '%s\n' "$ours" | grep -qE '^\++ exec gosu'; then fail "as configured, gosu was executed"; else pass "as configured, gosu is never executed"; fi
if printf '%s\n' "$ours" | grep -qxF "PID1-UID 70" && printf '%s\n' "$ours" | grep -q 'ready to accept connections'; then
    pass "the server came up as uid 70"
else
    fail "the server did not come up as uid 70"
fi
root="$(trace --cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add CHOWN --cap-add FOWNER --cap-add DAC_OVERRIDE)"
if printf '%s\n' "$root" | grep -qE '^\++ exec gosu postgres'; then pass "control: started as root, the trace shows gosu"; else fail "control: the trace cannot see gosu"; fi

echo "Failures: $FAILURES"
[ "$FAILURES" -eq 0 ]
