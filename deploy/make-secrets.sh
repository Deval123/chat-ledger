#!/bin/sh
# deploy/make-secrets.sh: writes the demo's secrets on the VPS (task 13, D6). Run as root; no value is typed, shown or logged.
#
#   sudo sh make-secrets.sh                         # the six generated values, in /opt/chat-ledger/secrets
#   sudo sh make-secrets.sh --tunnel-token          # the Cloudflare tunnel token, pasted on standard input (not echoed)
#   sudo sh make-secrets.sh [--tunnel-token] DIR    # another directory (the local and CI runs)
#
# Layout (deploy/compose.yaml mounts each file with `secrets:`):
#   DIR/        root:root 0700
#   DIR/db/     root:root 0700   files root:70    0440   read by PostgreSQL (uid 70)
#   DIR/app/    root:root 0700   files root:65532 0440   read by the chat-ledger image and cloudflared (uid 65532)
# A value that both uids read (the migrate, application and demo operator logins) is written twice, once per group.
# Bind mounts are resolved by the Docker daemon (root), so the containers need no access to the directories, only to their own files.
#
# Each generated value is 32 bytes from /dev/urandom as 64 hex characters, with no trailing newline. The script refuses to overwrite:
# DIR must not exist yet (the six values), and DIR/app/tunnel_token must not exist (the token). Nothing is half-written: the values are
# made in a temporary directory beside DIR and moved into place at the end.
#
# CHAT_LEDGER_DB_GID and CHAT_LEDGER_APP_GID override the two groups (only for a test on a host where they differ).
set -eu
umask 077

MODE=values
if [ "${1:-}" = "--tunnel-token" ]; then
    MODE=token
    shift
fi
DIR="${1:-/opt/chat-ledger/secrets}"
if [ "$#" -gt 1 ]; then
    echo "Usage: make-secrets.sh [--tunnel-token] [DIR]" >&2
    exit 2
fi
case "$DIR" in
    /*) ;;
    *) echo "Refused: DIR must be an absolute path." >&2; exit 2 ;;
esac
DB_GID="${CHAT_LEDGER_DB_GID:-70}"
APP_GID="${CHAT_LEDGER_APP_GID:-65532}"

if [ "$(id -u)" != 0 ]; then
    echo "Refused: run as root (sudo), so the files can be given to root and the readers' groups." >&2
    exit 1
fi

# put <file> <group>: the value on standard input becomes <file>, root:<group> 0440.
put() {
    cat > "$1"
    chown "0:$2" "$1"
    chmod 0440 "$1"
}

random_hex() {
    value="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    if [ "${#value}" -ne 64 ]; then
        echo "Refused: /dev/urandom gave fewer than 32 bytes." >&2
        exit 1
    fi
    printf '%s' "$value"
}

if [ "$MODE" = token ]; then
    if [ ! -d "$DIR/app" ]; then
        echo "Refused: $DIR/app does not exist. Write the generated values first." >&2
        exit 1
    fi
    if [ -e "$DIR/app/tunnel_token" ]; then
        echo "Refused: $DIR/app/tunnel_token exists. Remove it first (shred -u) if the token was rotated." >&2
        exit 1
    fi
    if [ -t 0 ]; then
        echo "Paste the tunnel token, then press Enter (it is not shown):" >&2
        stty -echo
        trap 'stty echo' EXIT INT TERM
    fi
    IFS= read -r token || true
    if [ -t 0 ]; then
        stty echo
        echo >&2
    elif [ -n "$(cat)" ]; then
        # Piped input: one line only. More is refused, not cut.
        echo "Refused: the token is more than one line." >&2
        exit 1
    fi
    # A token is one line of base64 text. Anything else is refused, not repaired.
    case "$token" in
        ''|*[!A-Za-z0-9+/=_-]*)
            echo "Refused: the token is empty or holds characters a tunnel token does not have." >&2
            exit 1 ;;
    esac
    if [ "${#token}" -gt 4096 ]; then
        echo "Refused: the token is longer than 4096 characters." >&2
        exit 1
    fi
    printf '%s' "$token" | put "$DIR/app/tunnel_token.new.$$" "$APP_GID"
    mv "$DIR/app/tunnel_token.new.$$" "$DIR/app/tunnel_token"
    echo "Written: $DIR/app/tunnel_token (root:$APP_GID 0440)."
    exit 0
fi

if [ -e "$DIR" ]; then
    echo "Refused: $DIR exists. The secrets are written once; nothing was changed." >&2
    exit 1
fi
NEW="$DIR.new.$$"
trap 'rm -rf "$NEW"' EXIT INT TERM
mkdir -m 0700 "$NEW" "$NEW/db" "$NEW/app"

random_hex | put "$NEW/db/pg_superuser_password" "$DB_GID"
random_hex | put "$NEW/db/provisioner_password" "$DB_GID"
random_hex | put "$NEW/app/app_secret" "$APP_GID"
for shared in migrate_password app_password demo_operator_password; do
    random_hex | put "$NEW/db/$shared" "$DB_GID"
    put "$NEW/app/$shared" "$APP_GID" < "$NEW/db/$shared"
done

mv "$NEW" "$DIR"
trap - EXIT INT TERM
echo "Written: $DIR/db (5 files, root:$DB_GID 0440) and $DIR/app (4 files, root:$APP_GID 0440). Add the tunnel token with --tunnel-token."
