#!/bin/sh
# deploy/test-upgrade.sh: the rehearsal of an upgrade, with its migration if the release has one (task 12b), on a throwaway Linux
# Docker host. Never on the VPS.
# Run as root, from the repository root, with both images loaded:
#
#   sudo sh deploy/test-upgrade.sh <old image> <new image> /tmp/chat-ledger-upgrade
#
# It builds the stack from nothing with the OLD image (as deploy/test-stack.sh does), uses it (the smoke script runs a scenario, so every
# demo workspace has seeded orders and at least one has matches), then upgrades to the NEW image exactly as RUNBOOK section 5 says for a
# release with a migration: stop refresh, run migrate, up web and refresh. A probe asks web's /health once a second throughout. After
# the upgrade: the smoke script again, and, for every workspace, the acceptance postings the new books check reads (one live
# ORDER_ACCEPTED per payable order, none for a cancelled one) and the trial balance. It prints the interruption (seconds without a 200)
# and how long the migration took. Exit 1 on any failure; WORKDIR is removed at the end.
set -eu
OLD="${1:?Usage: test-upgrade.sh OLD_IMAGE NEW_IMAGE WORKDIR}"
NEW="${2:?Usage: test-upgrade.sh OLD_IMAGE NEW_IMAGE WORKDIR}"
WORK="${3:?Usage: test-upgrade.sh OLD_IMAGE NEW_IMAGE WORKDIR (absolute, must not exist)}"
CURL="curlimages/curl:8.23.0@sha256:3fb7e13e5d179a845c97d78204f3f1064d1907037f26a3ebf91710571151fd93"
case "$WORK" in /*) ;; *) echo "WORKDIR must be absolute." >&2; exit 2 ;; esac
if [ -e "$WORK" ]; then echo "Refused: $WORK exists." >&2; exit 2; fi
# By id: NEW may itself be chat-ledger:test (CI's image-build job), the tag the compose override runs; it is put back on NEW at the end.
OLD_ID="$(docker image inspect -f '{{.Id}}' "$OLD")"
NEW_ID="$(docker image inspect -f '{{.Id}}' "$NEW")"
if [ "$OLD_ID" = "$NEW_ID" ]; then echo "Refused: the two images are the same." >&2; exit 2; fi
mkdir -m 0750 "$WORK"
mkdir -m 0755 "$WORK/initdb"
cp deploy/compose.yaml deploy/compose.test.yaml "$WORK/"
cp docker/initdb/01_create_roles.sh "$WORK/initdb/"
chmod 0755 "$WORK/initdb/01_create_roles.sh"
sh deploy/make-secrets.sh "$WORK/secrets"
printf '%s\n' "eyJhIjoibWFkZS11cCIsInQiOiJtYWRlLXVwIiwicyI6Im1hZGUtdXAifQ==" | sh deploy/make-secrets.sh --tunnel-token "$WORK/secrets"
printf 'CHAT_LEDGER_DIGEST=sha256:%064d\nAPP_SECURITY_TRUSTED_PROXY_HOPS=0\nDEMO_POOL=10\n' 0 > "$WORK/.env"
C="docker compose -f $WORK/compose.yaml -f $WORK/compose.test.yaml"
PROBE=chat-ledger-upgrade-probe
cleanup() {
    docker tag "$NEW_ID" chat-ledger:test || true
    docker rm -f "$PROBE" >/dev/null 2>&1 || true
    $C logs --no-color > "$WORK.log" 2>&1 || true
    $C --profile ops down --volumes --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM
wait_healthy() { # wait_healthy <container> <tries>
    i=0
    while [ "$i" -lt "$2" ]; do
        [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = healthy ] && return 0
        i=$((i + 1)); sleep 3
    done
    echo "FAIL $1 did not become healthy" >&2
    return 1
}
smoke() {
    docker run --rm --network chat-ledger_edge --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
        -v "$PWD/deploy/smoke.sh:/smoke.sh:ro" --entrypoint sh "$CURL" /smoke.sh http://web:8080
}
psql_super() { # the database as its superuser, over the container's own socket (trusted there, register 144)
    $C exec -T db psql -U postgres -d chatledger -v ON_ERROR_STOP=1 -At -c "$1"
}

# 1. The old release, from nothing, used once.
docker tag "$OLD_ID" chat-ledger:test
$C up -d db
wait_healthy chat-ledger-db-1 40
$C run --rm migrate
$C run --rm refresh demo-init
$C run --rm refresh demo-refresh --pool 10
$C up -d web refresh
wait_healthy chat-ledger-web-1 80
smoke
before="$(psql_super "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
echo "INFO schema before: V$before"

# 2. The probe: one request a second to /health, from the edge network, written with the time.
docker run -d --name "$PROBE" --network chat-ledger_edge --read-only --cap-drop ALL --security-opt no-new-privileges \
    --entrypoint sh "$CURL" -c 'while :; do printf "%s %s\n" "$(date -u +%H:%M:%S)" "$(curl -s -o /dev/null -m 2 -w "%{http_code}" http://web:8080/health)"; sleep 1; done' >/dev/null
sleep 5

# 3. The upgrade, as RUNBOOK section 5 says for a release with a migration.
docker tag "$NEW_ID" chat-ledger:test
upgrade_start="$(date +%s)"
$C stop refresh
migrate_start="$(date +%s)"
$C run --rm migrate
migrate_end="$(date +%s)"
$C up -d web refresh
wait_healthy chat-ledger-web-1 80
upgrade_end="$(date +%s)"
sleep 5
docker logs "$PROBE" > "$WORK/health.log" 2>/dev/null
docker rm -f "$PROBE" >/dev/null
after="$(psql_super "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
echo "INFO schema after: V$after"
# The schema ends at the last migration of this repository, never below where it started. A release without a migration (the
# deployed release already has the latest one) is a valid upgrade: before and after are then equal.
latest="$(find web/src/main/resources/db/migration -name 'V*__*.sql' | sed -n 's|^.*/V\([0-9]*\)__.*\.sql$|\1|p' | sort -n | tail -n 1 | sed 's/^0*//')"
if [ "$after" != "$latest" ] || [ "$after" -lt "$before" ]; then
    echo "FAIL the schema is V$after after migrate; this repository's last migration is V$latest, and it started at V$before"; exit 1
fi
if [ "$after" -eq "$before" ]; then echo "INFO no migration in this release (already at V$after)"; fi
echo "INFO migrate took $((migrate_end - migrate_start))s; the upgrade took $((upgrade_end - upgrade_start))s end to end (no pull: images are local)"
# The windows without a 200, as RUNBOOK section 5's probe summary prints them.
awk '$2 != 200 { if (!s) s = $1; e = $1; n++ } $2 == 200 && s { print "INFO " s " to " e ": " n " samples without 200"; s = ""; n = 0 }
     END { if (s) print "INFO " s " to " e ": " n " samples without 200 (still down)" }' "$WORK/health.log"
echo "INFO probe samples: $(wc -l < "$WORK/health.log")"
FAILURES=0

# 4. The release works, and the data the old release wrote passes the new books rules.
if smoke; then echo "PASS the smoke script after the upgrade"; else echo "FAIL the smoke script after the upgrade"; FAILURES=$((FAILURES + 1)); fi
bad="$(psql_super "SELECT t.slug || ': ' || live || ' live acceptance postings for ' || payable || ' payable orders, ' || cancelled_live || ' cancelled still posted, trial ' || trial
  FROM tenant t
  CROSS JOIN LATERAL (SELECT
     (SELECT count(*) FROM ledger_txn x WHERE x.tenant_id = t.id AND x.kind = 'ORDER_ACCEPTED'
         AND NOT EXISTS (SELECT 1 FROM ledger_txn r WHERE r.tenant_id = x.tenant_id AND r.reverses_txn_id = x.id)) AS live,
     (SELECT count(*) FROM orders o WHERE o.tenant_id = t.id AND o.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID')) AS payable,
     (SELECT count(*) FROM orders o WHERE o.tenant_id = t.id AND o.status = 'CANCELLED' AND EXISTS (SELECT 1 FROM ledger_txn x
         WHERE x.tenant_id = o.tenant_id AND x.kind = 'ORDER_ACCEPTED' AND x.ref_id = o.id
         AND NOT EXISTS (SELECT 1 FROM ledger_txn r WHERE r.tenant_id = x.tenant_id AND r.reverses_txn_id = x.id))) AS cancelled_live,
     (SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0) FROM ledger_entry e JOIN ledger_txn x ON x.id = e.txn_id
         WHERE x.tenant_id = t.id) AS trial) s
  WHERE live <> payable OR cancelled_live <> 0 OR trial <> 0")"
workspaces="$(psql_super "SELECT count(*) FROM tenant")"
payable="$(psql_super "SELECT count(*) FROM orders WHERE status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID')")"
if [ -z "$bad" ] && [ "$payable" -gt 0 ]; then
    echo "PASS every workspace ($workspaces) has one live acceptance posting per payable order ($payable in all) and a zero trial balance"
else
    echo "FAIL books after the upgrade (payable orders: $payable): $bad"; FAILURES=$((FAILURES + 1))
fi
echo "Failures: $FAILURES"
[ "$FAILURES" -eq 0 ]
