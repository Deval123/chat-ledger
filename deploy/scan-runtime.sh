#!/bin/sh
# deploy/scan-runtime.sh: scans the four images that run on the VPS for fixable HIGH or CRITICAL findings (task 13). Read-only: Trivy
# reads each image from its registry and changes nothing. Run from the repository root (the owner, or the weekly "Pinned digests"
# workflow):
#
#   sh deploy/scan-runtime.sh ghcr.io/deval123/chat-ledger@sha256:<the digest in /opt/chat-ledger/.env>
#
# The images: the application image (the argument), and every image compose.yaml and hops-echo.yaml pin by digest (PostgreSQL,
# cloudflared, the hops echo). CI's image job scans the application image on every push; this catches what appears later, against
# pins that have not changed, and the images CI does not build.
#
# Three acceptances, each named in the register and nothing else left out. No ignore file:
#   PostgreSQL (155): the findings in /usr/local/bin/gosu are left out only when deploy/check-gosu.sh passes in the same run.
#   cloudflared (156): zlib1g and CVE-2026-78661 only, and only when deploy/check-cloudflared.sh passes in the same run.
#   the hops echo (157): time-boxed to the hops measurement; listed, not failed.
# Everything else in those images, and the application image, is scanned in full. Exit 0 when every image is clean, 1 when any has a finding or could not
# be read, 2 for a usage error. Needs the Docker CLI.
set -u
TRIVY="aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb"
APP="${1:-}"
case "$APP" in
    ghcr.io/deval123/chat-ledger[@:]*) ;;
    *) echo "Usage: scan-runtime.sh ghcr.io/deval123/chat-ledger@sha256:<digest> (or :<commit>)" >&2; exit 2 ;;
esac
pinned="$(sed -n 's/^    image: \([a-z][^ ]*@sha256:[0-9a-f]\{64\}\)$/\1/p' deploy/compose.yaml deploy/hops-echo.yaml)"
if [ "$(printf '%s\n' "$pinned" | grep -c .)" -ne 3 ]; then
    echo "Expected 3 pinned images in compose.yaml and hops-echo.yaml, found: $pinned" >&2
    exit 2
fi

# findings <image>: one line per fixable HIGH or CRITICAL finding, class|type|package|id; fails when the image cannot be read.
findings() {
    docker run --rm "$TRIVY" image --image-src remote --platform linux/amd64 --severity HIGH,CRITICAL --ignore-unfixed \
        --exit-code 0 --no-progress --quiet --format template \
        --template '{{range .}}{{$c := .Class}}{{$t := .Type}}{{range .Vulnerabilities}}{{$c}}|{{$t}}|{{.PkgName}}|{{.VulnerabilityID}}{{"\n"}}{{end}}{{end}}' \
        "$1"
}

# The findings the cloudflared evidence covers, and nothing else (register 156): any zlib1g finding (the binary is static, so the
# image's libz is never loaded) and CVE-2026-78661 in golang.org/x/net (its html package, not linked). Another x/net finding is not
# covered: other x/net packages are linked.
CLOUDFLARED_COVERED='os-pkgs\|debian\|zlib1g\|CVE-[0-9]+-[0-9]+|lang-pkgs\|gobinary\|golang\.org/x/net\|CVE-2026-78661'

scan() { # scan <image> [trivy flags...]: 0 when clean
    ref="$1"; shift
    docker run --rm "$TRIVY" image --image-src remote --platform linux/amd64 --severity HIGH,CRITICAL --ignore-unfixed \
        --exit-code 1 --no-progress "$@" "$ref"
}

failed=0
for ref in $APP $pinned; do
    echo "== $ref"
    case "$ref" in
        postgres:*)
            if sh deploy/check-gosu.sh; then
                echo "ACCEPTED the findings in /usr/local/bin/gosu: check-gosu.sh passed in this run (register 155)"
                scan "$ref" --skip-files /usr/local/bin/gosu; rc=$?
            else
                echo "NOT ACCEPTED: check-gosu.sh failed, so gosu is scanned like everything else"
                scan "$ref"; rc=$?
            fi ;;
        cloudflare/cloudflared:*)
            if sh deploy/check-cloudflared.sh; then
                if list="$(findings "$ref")"; then
                    printf '%s\n' "$list" | grep . | grep -xE "$CLOUDFLARED_COVERED" | sed 's/^/ACCEPTED (check-cloudflared.sh passed in this run, register 156): /'
                    uncovered="$(printf '%s\n' "$list" | grep . | grep -vxE "$CLOUDFLARED_COVERED")"
                    if [ -n "$uncovered" ]; then printf '%s\n' "$uncovered" | sed 's/^/NOT COVERED: /'; rc=1; else rc=0; fi
                else
                    rc=1
                fi
            else
                echo "NOT ACCEPTED: check-cloudflared.sh failed, so every finding counts"
                scan "$ref"; rc=$?
            fi ;;
        traefik/whoami:*)
            # Time-boxed (register 157): accepted only for the hops measurement, the one time it runs (check-host.sh fails if a
            # hops-echo container exists at any other time). Its findings are listed every week, not failed; it must still be read.
            if list="$(findings "$ref")"; then
                printf '%s\n' "$list" | grep . | sed 's/^/LISTED (time-boxed to the hops measurement, register 157): /'
                rc=0
            else
                rc=1
            fi ;;
        *)
            scan "$ref"; rc=$? ;;
    esac
    if [ "$rc" -eq 0 ]; then
        echo "PASS     $ref (clean, or only what the register accepts)"
    else
        echo "FINDINGS $ref (or it could not be read): see RUNBOOK.md, 'When a scan finds something'"
        failed=$((failed + 1))
    fi
done
echo "Images with findings: $failed."
[ "$failed" -eq 0 ]
