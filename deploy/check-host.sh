#!/bin/sh
# deploy/check-host.sh: read-only checks of chat-ledger on the shared VPS (task 13, design stop sections 4, 6 and 7).
#
#   sudo sh /opt/chat-ledger/check-host.sh
#
# Changes nothing: it only reads Docker, PostgreSQL settings and the host's sockets and firewall. Everything it touches is scoped to the
# chat-ledger project (compose file named, containers prefixed chat-ledger-). Prints PASS, FAIL or INFO per line; exit 1 on any FAIL.
set -u
COMPOSE="docker compose -f /opt/chat-ledger/compose.yaml"
FAILURES=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAILURES=$((FAILURES + 1)); }
info() { echo "INFO $1"; }

if [ "$(id -u)" != 0 ]; then
    echo "Run with sudo: Docker on this host is root-only." >&2
    exit 2
fi

# 1. PostgreSQL logs no statement text and no values (section 4).
EXPECTED="log_error_verbosity=terse
log_min_duration_statement=-1
log_min_error_statement=panic
log_parameter_max_length=0
log_parameter_max_length_on_error=0
log_statement=none"
ACTUAL="$($COMPOSE exec -T db psql -U postgres -d chatledger -At -c "SELECT name || '=' || setting FROM pg_settings WHERE name IN ('log_error_verbosity','log_statement','log_min_duration_statement','log_min_error_statement','log_parameter_max_length','log_parameter_max_length_on_error') ORDER BY 1" 2>/dev/null)"
if [ "$ACTUAL" = "$EXPECTED" ]; then pass "PostgreSQL log settings"; else fail "PostgreSQL log settings: got [$ACTUAL]"; fi

# 2. Size against the 2.4 GB line (D8).
BYTES="$($COMPOSE exec -T db psql -U postgres -d chatledger -At -c "SELECT pg_database_size('chatledger')" 2>/dev/null)"
case "$BYTES" in
    ''|*[!0-9]*) fail "database size could not be read" ;;
    *) if [ "$BYTES" -lt 2400000000 ]; then pass "database size $BYTES bytes, under the 2.4 GB line"
       else fail "database size $BYTES bytes: at or over the 2.4 GB line, run the reset (RUNBOOK.md)"; fi ;;
esac

# 3. The containers, their state and their memory.
info "containers:"
$COMPOSE ps --format 'table {{.Name}}\t{{.Status}}'
info "memory and CPU now:"
# shellcheck disable=SC2046 # one argument per container id
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' $(docker ps -q --filter label=com.docker.compose.project=chat-ledger)

# 4. No published port, no privileged container, no host network, no socket mount.
for id in $(docker ps -aq --filter label=com.docker.compose.project=chat-ledger); do
    name="$(docker inspect -f '{{.Name}}' "$id")"
    ports="$(docker inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{$p}} {{end}}' "$id")"
    if [ -z "$ports" ]; then pass "$name publishes no port"; else fail "$name publishes $ports"; fi
    flags="$(docker inspect -f '{{.HostConfig.Privileged}} {{.HostConfig.NetworkMode}} {{.HostConfig.ReadonlyRootfs}}' "$id")"
    case "$flags" in
        "false "*" true") pass "$name: not privileged, read-only root" ;;
        *) fail "$name: privileged/network/read-only is [$flags]" ;;
    esac
    case "$flags" in *" host "*) fail "$name uses the host network" ;; esac
    if docker inspect -f '{{range .Mounts}}{{.Source}} {{end}}' "$id" | grep -q 'docker.sock'; then fail "$name mounts the Docker socket"; fi
done

# 4a. The hops echo is time-boxed to the hops measurement (register 157): at any other time no hops-echo container may exist.
echo_ids="$(docker ps -aq --filter label=com.docker.compose.project=chat-ledger --filter label=com.docker.compose.service=hops-echo)"
if [ -z "$echo_ids" ]; then pass "no hops echo container"
else fail "a hops echo container exists outside the measurement: remove it (RUNBOOK.md section 4, step 5)"; fi

# 4b. Who runs, as which uid (db only as 70, so gosu never runs: register 155), on a read-only root, without capabilities.
if sh /opt/chat-ledger/check-processes.sh; then pass "every process runs as its own uid"; else fail "a process check failed (above)"; fi

# 5. Nothing new listening on the host (only SSH, plus whatever was there before; LLMNR 5355 should be gone, section 7).
info "listening sockets on the host:"
ss -lntup
if ss -lntup | grep -q ':5355 '; then fail "LLMNR still listens on 5355 (section 7, step 1)"; else pass "LLMNR does not listen"; fi

# 6. The metadata address (D17). Reported, not decided here.
info "firewall backend: $(docker info --format '{{.FirewallBackend.Driver}}' 2>/dev/null || echo unknown)"
SUBNET="$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' chat-ledger_egress 2>/dev/null)"
info "chat-ledger egress subnet: ${SUBNET:-none}"
if iptables -S DOCKER-USER 2>/dev/null | grep -q -- "-s ${SUBNET:-none} -d 169.254.169.254/32 .*-j DROP"; then
    pass "a DROP rule for the egress subnet to 169.254.169.254 exists"
else
    info "no DROP rule for $SUBNET to 169.254.169.254 in DOCKER-USER (iptables); see RUNBOOK.md, 'The metadata address'"
fi

# 7. The secret files (D6). The web process reads its two through Spring's configtree, which does not check modes, so it is checked here.
S=/opt/chat-ledger/secrets
expect_stat() { # expect_stat <path> <uid:gid mode>
    got="$(stat -c '%u:%g %a' "$1" 2>/dev/null)"
    if [ "$got" = "$2" ]; then pass "$1 is $2"; else fail "$1 is [$got], expected $2"; fi
}
expect_stat "$S" "0:0 700"
expect_stat "$S/db" "0:0 700"
expect_stat "$S/app" "0:0 700"
for f in pg_superuser_password migrate_password app_password provisioner_password demo_operator_password; do expect_stat "$S/db/$f" "0:70 440"; done
for f in migrate_password app_password demo_operator_password app_secret tunnel_token; do expect_stat "$S/app/$f" "0:65532 440"; done
extra="$(find "$S" -mindepth 2 | grep -vE "^$S/(db/(pg_superuser_password|migrate_password|app_password|provisioner_password|demo_operator_password)|app/(migrate_password|app_password|demo_operator_password|app_secret|tunnel_token))$")"
if [ -z "$extra" ]; then pass "no other file under $S"; else fail "unexpected files under $S: $extra"; fi

# 8. Room on the host.
info "memory:"; free -m
info "disk:"; df -h /var/lib/docker /opt

echo "Failures: $FAILURES"
[ "$FAILURES" -eq 0 ]
