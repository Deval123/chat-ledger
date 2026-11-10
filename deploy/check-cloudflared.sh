#!/bin/sh
# deploy/check-cloudflared.sh: the evidence that the findings accepted in the cloudflared image cannot be reached here (task 13,
# register 156). Read-only; needs the Docker CLI. Run from the repository root, by the weekly "Pinned digests" workflow and by hand:
#
#   sh deploy/check-cloudflared.sh              (the image compose.yaml pins, both architectures)
#
# The scanner finds zlib1g (an OS package of the image) and golang.org/x/net CVE-2026-78661 (in its html package). They are accepted
# only while every check below passes, re-run every week; any failure ends the acceptance (scan-runtime.sh then fails on them again):
#   1. compose.yaml runs the image's own entrypoint (`cloudflared`, nothing else): the tunnel service sets no entrypoint;
#   2. for the amd64 and the arm64 image: the entrypoint is ["cloudflared"]; /usr/local/bin/cloudflared has no program interpreter:
#      no dynamic loader runs, so no shared library (libz included) is loaded, neither at start nor by dlopen;
#   3. the binary keeps its symbol table (at least 1,000 symbols; a stripped binary has none), so an absent package is really absent;
#   4. no symbol of golang.org/x/net/html is in it: the package of CVE-2026-78661 is not linked.
# Exit 0 when all pass, 1 otherwise, 2 when the image cannot be found.
set -u
FAILURES=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAILURES=$((FAILURES + 1)); }
# The analysis tools (readelf, go tool nm), pinned like everything else.
TOOLS="golang:1.25-bookworm@sha256:3b4a11519ad929d1e1d261a12cff056f0c85b735253d7d861346b9c6f8b36437"

PINNED="$(sed -n 's/^    image: \(cloudflare\/cloudflared:[^ ]*@sha256:[0-9a-f]\{64\}\)$/\1/p' deploy/compose.yaml)"
if [ -z "$PINNED" ]; then
    echo "No pinned cloudflared image in deploy/compose.yaml." >&2
    exit 2
fi

# 1. The tunnel service runs the image's entrypoint.
tunnel_block="$(awk '/^  tunnel:$/ { on = 1; next } /^  [a-z-]+:$/ { on = 0 } on' deploy/compose.yaml)"
if printf '%s\n' "$tunnel_block" | grep -q '^    entrypoint:'; then fail "the tunnel service overrides the entrypoint"
else pass "the tunnel service runs the image's own entrypoint"; fi

# The images to examine: an argument names one (only the tests do, to show each check can fail); otherwise both architectures of
# the pinned image, each by its own manifest digest.
if [ -n "${1:-}" ]; then
    targets="$1"
else
    targets="$(docker buildx imagetools inspect --raw "$PINNED" | tr -d ' \n' | grep -oE '"digest":"sha256:[0-9a-f]{64}"[^}]*"architecture":"(amd64|arm64)"' \
        | sed -E 's/"digest":"(sha256:[0-9a-f]{64})".*"architecture":"([a-z0-9]+)"/\2=cloudflare\/cloudflared@\1/')"
    if [ "$(printf '%s\n' "$targets" | grep -c .)" -ne 2 ]; then
        echo "Could not read the amd64 and arm64 manifests of $PINNED." >&2
        exit 2
    fi
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM
for target in $targets; do
    case "$target" in
        *=*) arch="${target%%=*}"; ref="${target#*=}"; platform="--platform=linux/$arch" ;;
        *) arch=local; ref="$target"; platform="" ;;
    esac
    # shellcheck disable=SC2086 # empty or one flag
    docker pull -q $platform "$ref" >/dev/null 2>&1 || true
    entrypoint="$(docker image inspect -f '{{json .Config.Entrypoint}}' "$ref" 2>/dev/null)"
    if [ "$entrypoint" = '["cloudflared"]' ]; then pass "$arch: the entrypoint is cloudflared"; else fail "$arch: the entrypoint is [$entrypoint]"; fi
    # shellcheck disable=SC2086
    cid="$(docker create $platform "$ref" 2>/dev/null)"
    if [ -z "$cid" ] || ! docker cp -q "$cid:/usr/local/bin/cloudflared" "$WORK/cloudflared" 2>/dev/null; then
        fail "$arch: /usr/local/bin/cloudflared could not be read"
        [ -n "$cid" ] && docker rm "$cid" >/dev/null
        continue
    fi
    docker rm "$cid" >/dev/null
    report="$(docker run --rm --network none -v "$WORK:/x:ro" "$TOOLS" sh -c '
        echo "interp: $(readelf -l /x/cloudflared 2>&1 | grep -c "Requesting program interpreter")"
        echo "symbols: $(go tool nm /x/cloudflared 2>/dev/null | wc -l)"
        echo "html: $(go tool nm /x/cloudflared 2>/dev/null | grep -c " golang.org/x/net/html\.")"' 2>/dev/null)"
    value() { printf '%s\n' "$report" | sed -n "s/^$1: *//p"; }
    rm -f "$WORK/cloudflared"
    if [ "$(value interp)" = 0 ]; then
        pass "$arch: statically linked (no program interpreter, so no dynamic loader): libz is never loaded"
    else
        fail "$arch: not static: it requests a program interpreter, so shared libraries can be loaded"
    fi
    symbols="$(value symbols)"
    if [ "${symbols:-0}" -ge 1000 ]; then pass "$arch: the symbol table is there ($symbols symbols)"; else fail "$arch: only [$symbols] symbols: stripped, so absence proves nothing"; fi
    if [ "$(value html)" = 0 ]; then pass "$arch: golang.org/x/net/html is not linked"; else fail "$arch: golang.org/x/net/html is linked ($(value html) symbols)"; fi
done

echo "Failures: $FAILURES"
[ "$FAILURES" -eq 0 ]
