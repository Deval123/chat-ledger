#!/bin/sh
# deploy/smoke.sh: the public demo, checked from outside as a visitor sees it (task 13, design stop section 6).
#
#   sh deploy/smoke.sh https://ledger.nkap.dev
#
# POSIX sh and curl only; no secret, no account. It takes one demo workspace (one claim of the hour's sixty for the caller's address).
# Each check prints PASS or FAIL. Exit 0 when every check passes, 1 when any fails, 2 for a usage error.
#
# The session cookie is read from Set-Cookie and sent back by hand: it is Secure, so curl's own cookie jar would not send it over the
# plain-HTTP address the CI and local runs use (http://web:8080 on the edge network).
# shellcheck disable=SC2016 # the eval'd checks expand when they run, not when they are written
set -u

BASE="${1:-}"
case "$BASE" in
    http://*|https://*) ;;
    *) echo "Usage: sh deploy/smoke.sh <base URL, for example https://ledger.nkap.dev>" >&2; exit 2 ;;
esac
BASE="${BASE%/}"

CSP="default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'"
HSTS="max-age=31536000 ; includeSubDomains"
COOKIE_NAME="__Host-chatledger-session"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM
HEADERS="$WORK/headers"
BODY="$WORK/body"
LOCATIONS="$WORK/locations"
: > "$LOCATIONS"
COOKIE=""
STATUS=""
FAILURES=0

pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAILURES=$((FAILURES + 1)); }
check() { # check <description> <command...>: PASS when the command succeeds
    what="$1"; shift
    if "$@"; then pass "$what"; else fail "$what"; fi
}

# header <name>: the value of the last header with that name in the last response (case-insensitive), without the CR.
header() {
    tr -d '\r' < "$HEADERS" | awk -v n="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" '
        { i = index($0, ":"); if (i > 0 && tolower(substr($0, 1, i - 1)) == n) { v = substr($0, i + 1); sub(/^[ \t]+/, "", v); last = v; found = 1 } }
        END { if (found) print last }'
}
has_header() { tr -d '\r' < "$HEADERS" | grep -qi "^$1:"; }

# request <method> <path> [form data]: one request; sets STATUS, keeps the headers and body, records Location, follows the cookie.
request() {
    method="$1"; path="$2"; data="${3:-}"
    set -- -sS --max-time 20 -o "$BODY" -D "$HEADERS" -w '%{http_code}' -X "$method"
    if [ -n "$COOKIE" ]; then set -- "$@" -H "Cookie: $COOKIE_NAME=$COOKIE"; fi
    if [ "$method" = POST ]; then set -- "$@" -H 'Content-Type: application/x-www-form-urlencoded' --data-raw "$data"; fi
    STATUS="$(curl "$@" "$BASE$path" 2>"$WORK/curl-error")" || STATUS="000"
    : >> "$HEADERS"
    : >> "$BODY"
    location="$(header location)"
    if [ -n "$location" ]; then printf '%s\n' "$location" >> "$LOCATIONS"; fi
    set_cookie="$(tr -d '\r' < "$HEADERS" | grep -i "^set-cookie: *$COOKIE_NAME=" | tail -n 1)"
    if [ -n "$set_cookie" ]; then
        printf '%s\n' "$set_cookie" > "$WORK/set-cookie"
        COOKIE="$(printf '%s' "$set_cookie" | sed -e "s/^[^=]*=//" -e 's/;.*$//')"
    fi
}

# csrf: the CSRF token of the first form in the last body (Thymeleaf writes name="_csrf" value="...").
csrf() {
    tr -d '\n' < "$BODY" | sed -n 's/.*name="_csrf"[^>]*value="\([^"]*\)".*/\1/p' | head -n 1
}

body_has() { grep -qF "$1" "$BODY"; }
status_is() { [ "$STATUS" = "$1" ]; }
location_is() { [ "$(header location)" = "$1" ]; }
header_is() { [ "$(header "$1")" = "$2" ]; }
not_has_header() { ! has_header "$1"; }
cache_no_store() { header cache-control | grep -q 'no-store'; }

# Every <script> in the last body loads a file from /assets/ and nothing else (no inline script, no other origin).
scripts_only_from_assets() {
    tr -d '\n' < "$BODY" | grep -o '<script[^>]*>' > "$WORK/scripts" || true
    ! grep -v '^<script src="/assets/[^"]*"[^>]*>$' "$WORK/scripts" | grep -q .
}

echo "Smoke test of $BASE"

# 1. The entry.
request GET /
check "GET / redirects to /demo" eval 'status_is 302 && location_is /demo'
request GET /demo
check "GET /demo answers 200" status_is 200
check "the demo page says it is made-up" body_has "Nothing here is real."

# 2. Headers.
check "the content security policy is exactly the design's" header_is content-security-policy "$CSP"
check "HSTS: one year, includeSubDomains, no preload" header_is strict-transport-security "$HSTS"
# The application sends no Server header; through the tunnel, Cloudflare's edge adds its own, and only that value is accepted.
check "no Server header from the origin" eval 'not_has_header server || header_is server cloudflare'
check "no X-Powered-By header" not_has_header x-powered-by
check "pages are not stored" cache_no_store
check "no script outside /assets/ on /demo" scripts_only_from_assets

# 3. Health.
request GET /actuator/health
check "the actuator is not on the public port" eval '[ "$STATUS" != 200 ] && ! body_has UP'
SAVED_COOKIE="$COOKIE"
COOKIE=""
request GET /health
check "/health says UP and nothing else" eval 'status_is 200 && [ "$(cat "$BODY")" = "{\"status\":\"UP\"}" ]'
COOKIE="$SAVED_COOKIE"

# 4. One click into a demo workspace.
request GET /demo
TOKEN="$(csrf)"
check "the demo page carries a CSRF token" test -n "$TOKEN"
request POST /demo "_csrf=$TOKEN"
check "POST /demo leads to the workspace" eval 'status_is 302 && location_is /app'
cookie_flags() {
    [ -f "$WORK/set-cookie" ] && grep -qi 'secure' "$WORK/set-cookie" && grep -qi 'httponly' "$WORK/set-cookie" \
        && grep -qi 'samesite=strict' "$WORK/set-cookie" && ! grep -qi 'domain=' "$WORK/set-cookie"
}
check "the session cookie is __Host-, Secure, HttpOnly, SameSite=Strict, no Domain" cookie_flags

# 5. The board.
request GET /app
check "the board opens" status_is 200
check "the board says the data is made-up" body_has "Demo, made-up data"
check "no script outside /assets/ on the board" scripts_only_from_assets
check "the board is not stored" cache_no_store

# 6. One scenario, both steps.
request GET /app/demo
TOKEN="$(csrf)"
check "the scenario page carries a CSRF token" test -n "$TOKEN"
request POST /app/demo/scenarios/happy-path/both "_csrf=$TOKEN"
check "the happy path lands on the board" eval 'status_is 302 && location_is "/app?n=DEMO_PAYMENT_SENT"'
request GET "/app?n=DEMO_PAYMENT_SENT"
check "the board tells where the payment went" body_has "The payment was sent."

# 7. The books.
request GET /app/books
check "the books balance" eval 'status_is 200 && body_has "The books balance."'

# 8. Sign out.
request GET /app
TOKEN="$(csrf)"
request POST /logout "_csrf=$TOKEN"
check "sign-out leads to the sign-in page" eval 'status_is 302 && location_is "/login?logout"'
request GET /app
# On the demo deployment a visitor without a session is sent to the demo entry, not to the sign-in page.
check "after sign-out the board is closed: back to the demo entry" eval 'status_is 302 && location_is /demo'

# 9. Every redirect seen was relative: no http:// link behind the tunnel.
check "every Location header is a relative path" eval '! grep -v "^/" "$LOCATIONS" | grep -q .'

echo "Failures: $FAILURES"
[ "$FAILURES" -eq 0 ]
