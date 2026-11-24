# How Kiro was used: an inventory of what is in the repository

This file lists only what exists in the repository, with the path of each item. Every number below was counted in the repository at
the commit that adds this file; "How it was counted" says how, so it can be counted again.

## Specs

`.kiro/specs/chat-ledger/`, one spec for the whole product:

| File | What it holds |
|---|---|
| `.kiro/specs/chat-ledger/requirements.md` | Requirements 1 to 10, with the problem, users, glossary and non-goals |
| `.kiro/specs/chat-ledger/design.md` | The design, and the register of decisions (below) |
| `.kiro/specs/chat-ledger/tasks.md` | Tasks 1 to 14: 11 done, 3 open (10, 11 and 14) |

## Design stops

A design stop is the design of one task, written before its code. There are 8, one file per task:

| Task | File |
|---|---|
| 4, order extraction | `.kiro/specs/chat-ledger/task-4-design-stop.md` |
| 5, payment extraction | `.kiro/specs/chat-ledger/task-5-design-stop.md` |
| 6, matching engine | `.kiro/specs/chat-ledger/task-6-design-stop.md` |
| 7, suspicion rules | `.kiro/specs/chat-ledger/task-7-design-stop.md` |
| 8, board and summary UI | `.kiro/specs/chat-ledger/task-8-design-stop.md` |
| 9, simulators and demo tenant | `.kiro/specs/chat-ledger/task-9-design-stop.md` |
| 12, polish and accessibility (12a and 12b) | `.kiro/specs/chat-ledger/task-12-design-stop.md` |
| 13, deployment | `.kiro/specs/chat-ledger/task-13-design-stop.md` |

Tasks 1, 2, 3, 10, 11 and 14 have no design stop file.

## Steering

Three files in `.kiro/steering/`, with no front matter, so each is included in every interaction:

| File | What it sets |
|---|---|
| `.kiro/steering/tech.md` | Stack, module rules, test libraries, commit rules |
| `.kiro/steering/security.md` | Tenant scoping, append-only ledger, input validation, output encoding, secrets, phone hashing, machine endpoints, rate limits, AI extraction off by default, dependency checks |
| `.kiro/steering/product.md` | Who the product is for, and its principles: the seller's time, one plain sentence per automatic decision, advice only, cheap phones, English, integer money, no real data |

## Property-based tests (jqwik)

11 test classes hold 23 `@Property` methods:

| Module | Class |
|---|---|
| `ledger-core` | `ledger-core/src/test/java/dev/chatledger/ledger/LedgerPropertiesTest.java` |
| `extraction` | `extraction/src/test/java/dev/chatledger/extraction/OrderParserHostileTest.java` |
| `extraction` | `extraction/src/test/java/dev/chatledger/extraction/PaymentHostileTest.java` |
| `extraction` | `extraction/src/test/java/dev/chatledger/extraction/TextNormalizerTest.java` |
| `matching` | `matching/src/test/java/dev/chatledger/matching/MatchPlannerTest.java` |
| `matching` | `matching/src/test/java/dev/chatledger/matching/NameSimilarityTest.java` |
| `suspicion` | `suspicion/src/test/java/dev/chatledger/suspicion/rules/PairRulesTest.java` |
| `demo` | `demo/src/test/java/dev/chatledger/demo/DemoMaterialTest.java` |
| `web` | `web/src/test/java/dev/chatledger/demo/DemoMessagesTest.java` |
| `web` | `web/src/test/java/dev/chatledger/ledger/LedgerPostgresPropertyIT.java` |
| `web` | `web/src/test/java/dev/chatledger/matching/MatchingBooksPropertyIT.java` |

The generators of the ledger properties are in `ledger-core/src/test/java/dev/chatledger/ledger/LedgerGenerators.java` (no
`@Property` of its own).

**Example:** `MatchingBooksPropertyIT` (task 6.4) runs random sequences of payments, orders and seller decisions (confirm, reject, match
by hand, unmatch, set aside, take back, rematch) against PostgreSQL, through the real ingestion services and the matcher. After every
step it reads the books from the tables and checks `MatchInvariants`: no payment assigned more than its amount or to two orders, no
order assigned more than its total, the ledger balanced, and every status following from the matches. Its other properties: repeating
a decision changes nothing, and a repeated rematch reaches a point where it changes nothing.

## Mutation checks

No mutation-testing tool is configured (no plugin in any `pom.xml`). The checks were done by hand: a guard is broken on purpose, the
test must fail, and the file is restored from a copy (several headings in `docs/kiro-notes.md` say so). Where they are described:

- `docs/kiro-notes.md`: 15 sections headed "Mutation checks" or "Mutation pass".
- The design stops, which list the guards to break before the code is written: `task-4-design-stop.md` and `task-5-design-stop.md`
  ("Guards I will mutation-check"), `task-6-design-stop.md` ("Guards I will mutation-check"), `task-8-design-stop.md` ("Tests (stop
  2) and mutation checks"), `task-12-design-stop.md` ("Mutation checks (12b)"). `task-7`, `task-9` and `task-13` mention them in
  their text.
- The register in `design.md` cites mutation results in several items, for example 182.

## Register of decisions

`.kiro/specs/chat-ledger/design.md`, section "Trade-offs and weakened defenses", at the end of the file: one table, items **1 to
182**, numbered in order with no gap. Each row gives the defense weakened or not provided, why, and the compensation. The design's
sections for tasks 5 to 9 and 13 name the items that belong to them.

## Hooks

**The hooks were added after task 13, during task 14** (2026-11-24). None of them ran while tasks 1 to 13 were built. There are 3 hook files in `.kiro/hooks/`, with 4 hooks between them, and their scripts are in
`tools/kiro-hooks/`:

| File | Trigger | What it does |
|---|---|---|
| `.kiro/hooks/template-lint.json` | `PostFileSave` on a `.html` under `web/src/main/resources/templates/` | runs `TemplateLintTest` offline (`tools/kiro-hooks/template-lint.sh`); exit 1 with the failures when it fails |
| `.kiro/hooks/migration-reminder.json` (2 hooks) | `PostFileCreate` and `PostFileSave` on a `V*.sql` under `web/src/main/resources/db/migration/` | adds a reminder to the agent's context: a release with a migration needs its release note in `deploy/RUNBOOK.md` section 5, and the upgrade rehearsal `deploy/test-upgrade.sh` |
| `.kiro/hooks/commit-guard.json` | `PreToolUse` on the shell tool | refuses a `git commit` (exit 2) whose message holds a co-author trailer or a session line, that sets another author or committer address, or whose repository's `user.email` is not the owner's noreply address (`tools/kiro-hooks/commit-guard.py`) |

They are not the set suggested in `tasks.md` ("Kiro workflow to document as you go"). The owner chose three rules the project already
had. Limits:
- The commit guard sees only commands run through the agent's shell tool; a commit typed in a terminal does not pass through it.
- It lets a command through if its input is not valid JSON (fail open), so a broken payload never blocks every shell command.
- The template check runs Maven offline, so it needs the dependencies already in the local Maven repository.

## Not in the repository

- **No MCP configuration.** There is no `.kiro/settings/` directory and no `mcp.json` anywhere in the repository.

## How it was counted

- **Specs, design stops, steering:** the files in `.kiro/specs/chat-ledger/` and `.kiro/steering/`. Requirements are the
  `## Requirement N` headings of `requirements.md`. Tasks are the `- [x] N.` (done) and `- [ ] N.` (open) lines of `tasks.md`.
- **Property tests:** tracked `*.java` files with a line that starts with `@Property` (classes), and the number of such lines
  (methods).
- **Mutation sections:** headings of `docs/kiro-notes.md` that contain "Mutation checks" or "Mutation pass"; headings of the design
  stops that contain "mutation"; `pom.xml` files searched for "pitest" or "mutation".
- **Register:** rows of `design.md` that start with `| <number> |`. There are 182 rows, numbered 1 to 182 in file order.
- **Hooks:** the `.json` files in `.kiro/hooks/` (files), and the entries of their `hooks` arrays (hooks).
- **MCP:** tracked files searched for `.kiro/settings` and `mcp.json`, and the working tree for `mcp.json`.
