#!/bin/sh
# deploy/check-digests.sh: reports pinned image digests that have gone stale (task 13). Read-only: it asks the registries what each tag
# points to today and changes nothing. Run from the repository root (the owner, or the weekly "Pinned digests" workflow):
#
#   sh deploy/check-digests.sh
#
# For every `name:tag@sha256:<digest>` in the files below: CURRENT when the tag still points to the pinned digest, STALE when it has
# moved (a rebuilt base, usually with security fixes: see RUNBOOK.md, "Pinned digests go stale"), UNKNOWN when the registry could not
# be read. Exit 0 only when every pin is CURRENT; 1 otherwise; 2 when no pin was found. Needs the Docker CLI with buildx.
#
# A tag that names an exact release (cloudflared 2026.10.7, trivy 0.76.2, curl 8.23.0) rarely moves: a newer release has a new tag,
# which this cannot see. The image scan covers the application image's contents; the other releases are checked by hand.
set -u
FILES="Dockerfile deploy/compose.yaml deploy/hops-echo.yaml deploy/test-stack.sh .github/workflows/ci.yml
web/src/test/java/dev/chatledger/ledger/PostgresRelease.java web/src/test/java/dev/chatledger/deploy/MakeSecretsScriptIT.java"
# shellcheck disable=SC2086 # one argument per file
refs="$(grep -ohE '[a-z0-9][a-z0-9./_-]*:[A-Za-z0-9][A-Za-z0-9._-]*@sha256:[0-9a-f]{64}' $FILES | sort -u)"
if [ -z "$refs" ]; then
    echo "No pinned image found." >&2
    exit 2
fi
stale=0
unknown=0
for ref in $refs; do
    name="${ref%@*}"
    pinned="${ref#*@}"
    current="$(docker buildx imagetools inspect "$name" 2>/dev/null | sed -n 's/^Digest: *//p' | head -n 1)"
    if [ -z "$current" ]; then
        echo "UNKNOWN $name (the registry could not be read)"
        unknown=$((unknown + 1))
    elif [ "$current" = "$pinned" ]; then
        echo "CURRENT $name"
    else
        echo "STALE   $name pinned $pinned, the tag now points to $current"
        stale=$((stale + 1))
    fi
done
echo "Stale: $stale. Unknown: $unknown."
[ "$stale" -eq 0 ] && [ "$unknown" -eq 0 ]
