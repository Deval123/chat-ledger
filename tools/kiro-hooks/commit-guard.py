#!/usr/bin/env python3
"""tools/kiro-hooks/commit-guard.py: run by the Kiro hook .kiro/hooks/commit-guard.json before every shell command the agent runs.

Kiro passes the event as JSON on standard input: "tool_input.command" is the shell command, "tool_input.cwd" (or "cwd") where it runs.
A command that is not a `git commit` passes at once. A `git commit` is refused (exit 2, the reason on standard error, which Kiro gives
back to the agent) when:
  - its message holds a co-author trailer ("Co-Authored-By" followed by a colon, any case) or a session line (a line that starts with
    "Session" or "Session-Id" followed by a colon, or any text carrying a Kiro session id, "sess_" and a UUID). The message is read from
    -m and --message, --trailer, a here-document given to -F -, and a file given to -F; with --amend and no new message, the message
    kept is checked;
  - the command sets an author or committer address other than the owner's (--author, -c user.email=, GIT_AUTHOR_EMAIL=,
    GIT_COMMITTER_EMAIL=);
  - the user.email of the repository the commit runs in (the shell tool's directory, a `cd` before the commit, or `git -C`) is not the
    owner's noreply address.
It only sees commands run through the agent's shell tool; a commit typed in a terminal does not pass through it.
"""
import json
import os
import re
import shlex
import subprocess
import sys

NOREPLY = "28451130+Deval123@users.noreply.github.com"
GIT_COMMIT = re.compile(r"(?:^|[;&|(\s])git(?:\s+-C\s+\S+|\s+-c\s+\S+)*\s+commit\b")
CO_AUTHOR = re.compile(r"co-authored-by\s*:", re.IGNORECASE)
SESSION_LINE = re.compile(r"(?im)^\s*session(?:-id)?\s*:")
SESSION_ID = re.compile(r"sess_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.IGNORECASE)
HEREDOC = re.compile(r"<<-?\s*(['\"]?)(\w+)\1[^\n]*\n(.*?)\n\s*\2\s*(?:\n|$)", re.DOTALL)
ADDRESS = re.compile(r"<([^>]*)>")


def refuse(reason):
    print("Commit refused by the commit-guard hook: " + reason, file=sys.stderr)
    sys.exit(2)


def words_of(command):
    try:
        return shlex.split(command, posix=True)
    except ValueError:
        return command.split()


def address_of(value):
    found = ADDRESS.search(value)
    return (found.group(1) if found else value).strip().strip("'\"")


def main():
    event = json.load(sys.stdin)
    tool_input = event.get("tool_input") or {}
    command = tool_input.get("command") or ""
    if not GIT_COMMIT.search(command):
        return
    cwd = tool_input.get("cwd") or event.get("cwd") or os.getcwd()

    heredocs = [m.group(3) for m in HEREDOC.finditer(command)]
    words = words_of(HEREDOC.sub(" ", command))
    start = next((i for i, w in enumerate(words) if w == "commit" and "git" in words[:i]), len(words))

    # Where the commit runs: the last `cd` before it, then `git -C`.
    repo = cwd
    for i, w in enumerate(words[:start]):
        if w == "cd" and i + 1 < start:
            repo = os.path.join(repo, words[i + 1])
        if w == "-C" and i + 1 < start:
            repo = os.path.join(repo, words[i + 1])

    messages = list(heredocs)
    overrides = []
    for i, w in enumerate(words):
        value = words[i + 1] if i + 1 < len(words) else ""
        if w in ("-m", "--message", "--trailer"):
            messages.append(value)
        elif w.startswith(("--message=", "--trailer=")):
            messages.append(w.split("=", 1)[1])
        elif w in ("-F", "--file") and value not in ("", "-"):
            path = os.path.join(repo, value)
            if os.path.isfile(path):
                with open(path, encoding="utf-8", errors="replace") as fh:
                    messages.append(fh.read())
        elif w == "--author":
            overrides.append(value)
        elif w.startswith("--author="):
            overrides.append(w.split("=", 1)[1])
        elif w.startswith("user.email=") and i > 0 and words[i - 1] == "-c":
            overrides.append(w.split("=", 1)[1])
        elif re.match(r"GIT_(AUTHOR|COMMITTER)_EMAIL=", w):
            overrides.append(w.split("=", 1)[1])
    new_message = any(w in ("-m", "--message", "-F", "--file") or w.startswith(("--message=", "--file=")) for w in words)
    if "--amend" in words and not new_message:
        messages.append(subprocess.run(["git", "-C", repo, "log", "-1", "--format=%B"], capture_output=True, text=True).stdout)

    for text in messages + [command]:
        if CO_AUTHOR.search(text):
            refuse("the message holds a co-author trailer. Commits carry only the author's own name.")
        if SESSION_ID.search(text):
            refuse("the message holds a session id. Remove it.")
    for text in messages:
        if SESSION_LINE.search(text):
            refuse("the message holds a session line. Remove it.")

    for value in overrides:
        if address_of(value) != NOREPLY:
            refuse("the command sets the address '" + address_of(value) + "'; commits use " + NOREPLY + ".")

    email = subprocess.run(["git", "-C", repo, "config", "user.email"], capture_output=True, text=True).stdout.strip()
    if email != NOREPLY:
        refuse("the user.email of " + repo + " is '" + email + "', not " + NOREPLY + ".")


if __name__ == "__main__":
    main()
