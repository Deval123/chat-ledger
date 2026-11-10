#!/bin/sh
# deploy/check-processes.sh: every running chat-ledger container, as it runs (task 13; register 155 for db). Read-only; run as root
# where Docker is root-only. Used by test-stack.sh (CI and the local drill) and check-host.sh (the VPS):
#
#   sudo sh deploy/check-processes.sh
#
# For each container of the chat-ledger project: the configured user and every process uid are the expected one (web, refresh and
# migrate 65532, db 70, tunnel 65532), so nothing runs as root; the root file system is read-only; no capability
# is kept and no-new-privileges is set. Exit 1 on any failure.
set -u
FAILURES=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAILURES=$((FAILURES + 1)); }

ids="$(docker ps -q --filter label=com.docker.compose.project=chat-ledger)"
if [ -z "$ids" ]; then
    echo "FAIL no running chat-ledger container"
    exit 1
fi
for id in $ids; do
    service="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$id")"
    case "$service" in
        db) expected=70 ;;
        web|refresh|migrate|tunnel|hops-echo) expected=65532 ;;
        *) fail "$service: not a known service"; continue ;;
    esac
    user="$(docker inspect -f '{{.Config.User}}' "$id")"
    if [ "$user" = "$expected:$expected" ]; then pass "$service is configured as $user"; else fail "$service is configured as [$user]"; fi
    # Every process of the container, as the host sees it: its real uid and its command name, read from /proc by the PID column of
    # `docker top` (the one column every ps prints; busybox ps takes no -o).
    procs="$(docker top "$id" | awk 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == "PID") c = i; next } c { print $c }' | while read -r pid; do
        printf '%s %s\n' "$(awk '/^Uid:/ { print $2 }' "/proc/$pid/status" 2>/dev/null)" "$(cat "/proc/$pid/comm" 2>/dev/null)"
    done)"
    others="$(printf '%s\n' "$procs" | awk -v u="$expected" '$1 != u')"
    if [ -n "$procs" ] && [ -z "$others" ]; then pass "$service: every process runs as $expected"; else fail "$service: processes [$others]"; fi
    flags="$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}} {{.HostConfig.CapAdd}} {{.HostConfig.SecurityOpt}}' "$id")"
    # Compose writes no-new-privileges:true; the plain docker flag writes no-new-privileges. Both are the same setting.
    case "$flags" in
        "true [ALL] [] [no-new-privileges:true]"|"true [ALL] [] [no-new-privileges]")
            pass "$service: read-only root, no capability, no-new-privileges" ;;
        *) fail "$service: [$flags]" ;;
    esac
done
echo "Failures: $FAILURES"
[ "$FAILURES" -eq 0 ]
