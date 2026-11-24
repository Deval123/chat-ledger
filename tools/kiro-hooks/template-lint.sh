#!/bin/sh
# tools/kiro-hooks/template-lint.sh: run by the Kiro hook .kiro/hooks/template-lint.json when a Thymeleaf template is saved.
#
# Kiro passes the event as JSON on standard input; "file_path" is the absolute path of the saved file. Only a template under
# web/src/main/resources/templates/ runs the check (the hook's matcher says the same; this is the second look). It runs TemplateLintTest
# (no inline script, style or event handler, no unescaped output) offline, from the repository root.
# Exit 0: not a template, or the check passed. Exit 1: the check failed; its report is on standard error.
set -u
path="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("file_path") or "")')" || exit 0
case "$path" in
    */web/src/main/resources/templates/*.html) ;;
    *) exit 0 ;;
esac
root="$(git -C "$(dirname "$path")" rev-parse --show-toplevel 2>/dev/null)" || exit 0
log="$(mktemp)"
if (cd "$root" && mvn -o -q -pl web -am test -Dtest=TemplateLintTest -Dsurefire.failIfNoSpecifiedTests=false) > "$log" 2>&1; then
    echo "TemplateLintTest passed after saving ${path#"$root"/}."
    rm -f "$log"
    exit 0
fi
{
    echo "TemplateLintTest FAILED after saving ${path#"$root"/}:"
    grep -E 'AssertionError|expected|but|Expect|<<< FAIL|ERROR\]   ' "$log" | head -20
    echo "Full output: $log"
} >&2
exit 1
