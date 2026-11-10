#!/bin/sh
# deploy/test-stack.sh: the "from nothing" drill of the VPS stack, on a throwaway Linux Docker host (CI's image-build job, or a local
# Docker-in-Docker host). Never on the VPS. Run as root, from the repository root, with the image loaded as chat-ledger:test:
#
#   sudo sh deploy/test-stack.sh /tmp/chat-ledger-stack
#
# It lays out WORKDIR as /opt/chat-ledger is laid out (compose.yaml, initdb/, secrets/ by make-secrets.sh, .env), then: db up,
# migrate, demo-init, a first refresh pass, web and refresh up, the smoke script from a curl container on the edge network, check that
# web has no route out, and down --volumes. The tunnel is not started. Exit 1 on any failure; WORKDIR is removed at the end.
set -eu
WORK="${1:?Usage: test-stack.sh WORKDIR (absolute, must not exist)}"
CURL="curlimages/curl:8.23.0@sha256:3fb7e13e5d179a845c97d78204f3f1064d1907037f26a3ebf91710571151fd93"
case "$WORK" in /*) ;; *) echo "WORKDIR must be absolute." >&2; exit 2 ;; esac
if [ -e "$WORK" ]; then echo "Refused: $WORK exists." >&2; exit 2; fi

mkdir -m 0750 "$WORK"
# The role script's directory is bind-mounted as a directory, so PostgreSQL (uid 70) needs to list it: 0755, and it holds no secret.
mkdir -m 0755 "$WORK/initdb"
cp deploy/compose.yaml deploy/compose.test.yaml "$WORK/"
cp docker/initdb/01_create_roles.sh "$WORK/initdb/"
# Executable, so the image's entrypoint runs it in its own process; a non-executable script would be sourced into the entrypoint, and
# its file_env function would replace the entrypoint's own.
chmod 0755 "$WORK/initdb/01_create_roles.sh"
sh deploy/make-secrets.sh "$WORK/secrets"
# A made-up token of the right shape (base64 of made-up JSON); the tunnel is not started.
printf '%s\n' "eyJhIjoibWFkZS11cCIsInQiOiJtYWRlLXVwIiwicyI6Im1hZGUtdXAifQ==" | sh deploy/make-secrets.sh --tunnel-token "$WORK/secrets"
printf 'CHAT_LEDGER_DIGEST=sha256:%064d\nAPP_SECURITY_TRUSTED_PROXY_HOPS=0\nDEMO_POOL=10\n' 0 > "$WORK/.env"

C="docker compose -f $WORK/compose.yaml -f $WORK/compose.test.yaml"
cleanup() {
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

$C up -d db
wait_healthy chat-ledger-db-1 40
$C run --rm migrate
$C run --rm refresh demo-init
$C run --rm refresh demo-refresh --pool 10
started="$(date +%s)"
$C up -d web refresh
wait_healthy chat-ledger-web-1 80
echo "INFO web healthy $(( $(date +%s) - started ))s after up"

docker run --rm --network chat-ledger_edge --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
    -v "$PWD/deploy/smoke.sh:/smoke.sh:ro" --entrypoint sh "$CURL" /smoke.sh http://web:8080

if docker run --rm --network container:chat-ledger-web-1 --entrypoint curl "$CURL" -s -m 5 -o /dev/null https://www.cloudflare.com/; then
    echo "FAIL web reached the internet"; exit 1
fi
echo "PASS web has no route out"

# Who runs, as which uid, on which root (register 155 for db): web and refresh only as 65532, db only as 70, nothing as root, no gosu.
sh deploy/check-processes.sh

# An exposed port reads "8080/tcp"; only a published one has "->" (for example "0.0.0.0:8080->8080/tcp").
ports="$(docker ps --filter label=com.docker.compose.project=chat-ledger --format '{{.Names}} {{.Ports}}' | grep -e '->' || true)"
if [ -n "$ports" ]; then echo "FAIL published ports: $ports"; exit 1; fi
echo "PASS no container publishes a port"

# shellcheck disable=SC2046 # one argument per container id
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' $(docker ps -q --filter label=com.docker.compose.project=chat-ledger)
echo "PASS the stack ran from nothing"
