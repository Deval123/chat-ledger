# Task 6 design stop: matching engine

Status: **approved by the owner** on all seventeen decisions as recommended, with these changes (they override anything below that disagrees):

1. **Stop 2 is split.** Stop 2a: 6.1 scoring (`MatchScorer`, `NameSimilarity`, `MatchConfig`, `MatchingProperties` with startup bounds), V016 (columns, indexes, constraints, column-limited grants, transition and consistency triggers), `MatchRepository` with the owner-run tenant scoping test, `ChartOfAccounts`, `appendWithin` with the shared account and overflow checks. Stop 2b (after the owner's go): `MatchingService.apply` and the flows with their postings, the engine runs, the post-commit hook in both ingestion services, the `ORDER_ACCEPTED` posting, concurrency and rollback tests. Stop 3 as written.
2. **D1:** the stop 2b summary says exactly what the caller sees when the `ORDER_ACCEPTED` posting fails inside the ingestion transaction (the order is then not stored), and proves that a retry of the same external id works and never double-posts. Task 8 gets a line: an order that leaves `NEEDS_REVIEW` and gets its total must post `ORDER_ACCEPTED` at that moment.
3. **Task 9** gets a line: the demo seed must exercise every flow, with references and names that make the score work, and timestamps inside the plausibility window.
4. **Task 8** gets an optional line: a reconciliation check on the board.
5. **D6:** the kill switch stays on by default.

Progress: **stop 2a done** (6.1; see `docs/kiro-notes.md`, "Task 6, stop 2a"). Stop 2b and stop 3 wait for the owner's go.

Implementation notes that refine the text below: the sender's last-four bonus (3 points) applies only when the name already agrees; the planner (`MatchPlanner`) is part of 6.1 and is pure; the insert-state trigger is dropped (the CHECKs and the consistency triggers cover it); the upper bound of the suggestion threshold (90) is not a separate check, it follows from the automatic threshold being at most 100 and at least 10 above it; the commit-time check locks the order row only (the application locks payment then order, so a second lock in the trigger added nothing); apostrophes are dropped from names (`N'Diaye` is `ndiaye`).

Sources re-read: requirements 3 and 4 (and 2.4, 5, 9); the design (matching score, ledger postings, states, data model, register items 1 to 50); `tasks.md` tasks 6, 7, 8; migrations V004 to V011, V014, V015; `ledger-core`; `JdbcLedgerRepository`; `MatchScore` (scaffold); `Permission` and `Role`; the task 4 and task 5 services and repositories.

## Facts checked (and four gaps they reveal)

- `match` exists (V006, tenant columns and same-tenant foreign keys from V010) with states `AUTO, SUGGESTED, CONFIRMED, REJECTED, REVERSED`. The application role has **SELECT only** on `match`, `orders.status` and `payment.status` cannot be changed, and nothing writes `match` today.
- `ledger_txn` and `ledger_entry` are append-only (triggers, V007/V008), every transaction must balance at commit (deferred constraint trigger, V009), a reversal points at the original and there can be only one (`uq_ledger_txn_reverses`), and the application role may INSERT and SELECT them. `LedgerService` and `JdbcLedgerRepository` exist and are tested.
- **Gap 1: `JdbcLedgerRepository.append` opens its own transaction.** A posting made through it cannot be atomic with a match row and a status change. The ledger needs a "within this connection" variant (like `AuditLogRepository.appendWithin`).
- **Gap 2: no chart of accounts exists.** Provisioning creates a tenant and a user, not the four default accounts the design names.
- **Gap 3: nothing posts "order accepted".** Task 4 stores the order but makes no ledger entry, so "payment matched: credit Accounts receivable" would credit an account that was never debited.
- **Gap 4: the design's score table cannot produce a partial or over-payment match.** The amount signal only fires when the payment equals the order balance (50 points); a partial payment with every other signal scores at most 50, below the suggestion threshold of 60, so requirements 3.5 and 3.6 would be unreachable. The table needs amount tiers.
- Reused as they are: `ReferenceKeys` (payment `reference` against `orders.order_ref`), `PhoneKeys` hashes (`payment.sender_phone_hash` against `customer.phone_hash`, same key, both per tenant), `TextKeys` (accent-insensitive names), `TenantScope`, `AuditLogRepository.appendWithin`, `RateLimits`, the isolation registry and the owner-run scoping test pattern from task 5.
- Authorisation already exists: `CONFIRM_OR_OVERRIDE_MATCH` and `REVERSE_TRANSACTION` belong to SELLER only; a HELPER records orders and payments and sees the board.

## What I will build

| # | Piece | Where | Notes |
|---|---|---|---|
| 1 | `MatchScorer` (pure) and `MatchConfig` | `matching` | Signals, tiers, reason **codes** (no text), floors, tie margin. No database, no strings from messages in its output. |
| 2 | `NameSimilarity` (pure) | `matching` | Bounded token comparison on `TextKeys` keys. No regular expression. |
| 3 | `MatchingProperties` | `web` | `app.matching.*` with safe bounds; the application refuses to start outside them. |
| 4 | V016 migration | `web` | New `match` columns, indexes, constraints, **column-limited UPDATE grants**, transition and invariant triggers, one-posting-per-event unique index. |
| 5 | `MatchRepository` | `web` | Prepared statements only, every statement names the tenant, registered in `IsolationRegistry`, owner-run scoping test. |
| 6 | `ChartOfAccounts` | `web` | Four fixed accounts per tenant, created idempotently inside the matching transaction. |
| 7 | `JdbcLedgerRepository.appendWithin` (and `reverseWithin` for stop 3) | `web` | Same SQL as today, on the caller's connection; the account and overflow checks are shared with `LedgerService`. |
| 8 | `MatchingService` | `web` | `onPaymentRecorded`, `onOrderRecorded` (engine), `apply` (the one atomic step), later `confirm`, `reject`, `matchManually`, `unmatch`. |
| 9 | `MatchEvents` | `web` | Log takes ids, enums and counts only. |
| 10 | Tests | all | Unit (scoring, config bounds, name similarity, hostile text), integration (flows, postings, concurrency, grants, triggers, tenant scoping, rollback), property-based (stop 3). |

## Scoring: signals, weights and what text can forge

**The honest answer first.** The request says each signal should be one that "cannot be forged by text alone". No signal derived from an SMS can meet that: whoever can write the SMS can write its amount, reference, sender name, sender number and time. The system is therefore not secure because the score is unforgeable; it is secure because (a) **an automatic match needs several independent conditions at once**, (b) **a match moves no money**, (c) **every match is reversible and audited**, and (d) **the seller has the last word**. The table shows, for each signal, what the forger controls and what the system controls.

| Signal | Points | The text controls | The system controls | Forgeable by someone who writes the SMS? | Defense |
|---|---|---|---|---|---|
| Amount | exact 50; partial or over (up to 2x balance) 20; else 0 | the payment amount | the order balance, computed from locked rows and active matches | Yes, if they know the order total | Only an **exact** amount can reach auto; partial and over-payment are suggestions only; the balance is never taken from text |
| Reference | 25 | the code written in the transfer note | the order codes, and whether the code is **unique among open orders** | Yes, if they guess or know the code (codes are not secret) | Compared by `ReferenceKeys` equality only; if two open orders share the code the signal is **0** ("ambiguous"), so planting a duplicate code in another order cannot steal a payment; auto also needs the other floors |
| Sender identity | up to 15: phone hash equal 15; otherwise name agreement 12 (all tokens) or 8 (initial or prefix) plus 3 if the last four digits equal the customer's | the sender number and name | the customer's stored hash and name; the hash uses a per-tenant key the text cannot influence | Yes if the number is known (the number is on the customer's own order) | The number is compared as a keyed hash, never as text; a name alone is capped at 12 and is the weakest signal; identity can never reach auto alone |
| Time | 10 if the claimed `paid_at` is between 10 minutes before and 7 days after the order's `created_at` | the claimed time (already limited to 15 minutes ahead and 35 days back) | the order's `created_at` (server clock) | Yes, within the window | Lowest weight; a payment dated before the order scores 0 here and task 7 flags it |

**Structural floors for an automatic match** (not configuration, not relaxable by thresholds). All must hold, checked again inside the locked transaction and by a database trigger where the data allows it:

1. The payment is `RECEIVED` with a provider transaction id (so never `UNVERIFIED`, never `DUPLICATE`).
2. The amount is **exactly** the order balance (so partial and over-payment are never automatic).
3. The reference signal fired, and the reference is unique among open orders.
4. The identity signal is greater than 0.
5. The payment and order share the currency and the tenant.
6. No other candidate scores within the tie margin (10 points) of the best.
7. The payment was recorded by a SELLER or the machine feed, not by a HELPER (decision D5).
8. The kill switch `app.matching.auto-enabled` is on.

With the weights above, exact amount plus reference is 75, and the 85 threshold needs 10 more points. Floor 4 requires some identity, so the lowest automatic scores are 50 + 25 + 12 (full name agreement) = 87, 50 + 25 + 15 (phone hash) = 90, or 50 + 25 + 8 (initial or prefix) + 10 (time) = 93. Reference plus time alone (85) is not enough, because floor 4 fails.

**Candidates.** A payment is scored against the tenant's open orders (`PENDING_PAYMENT`, `PARTIALLY_PAID`, total known, same currency), most recent first, at most 200. An order is scored against open payments (`RECEIVED`, `UNVERIFIED`), at most 200. Never candidates: `DUPLICATE`, `UNPARSED`, `MATCHED` payments; `NEEDS_REVIEW`, `UNPARSED`, `PAID`, `CANCELLED` orders.

**Reasons are codes.** `reasons_json` holds `[{"code":"AMOUNT_EXACT","points":50},...]`: enum names and integers, never text from a message. The page renders the sentence from the code (and the interface language). A CHECK limits the column to letters, digits, `_`, `:`, `,`, `"`, `{`, `}`, `[`, `]` and space, so even a bug cannot store free text there.

## Thresholds as configuration (safe bounds)

| Key | Default | Bounds | If out of bounds |
|---|---|---|---|
| `app.matching.auto-threshold` | 85 | 75 to 100 | the application does not start |
| `app.matching.suggest-threshold` | 60 | 40 to 90 | the application does not start |
| (relation) | | auto at least suggest + 10 | the application does not start |
| `app.matching.auto-enabled` | true | boolean | kill switch: when false the engine only suggests |
| `app.matching.candidate-cap` | 200 | 10 to 500 | the application does not start |
| `app.matching.tie-margin` | 10 | 5 to 30 | the application does not start |

Weights, tiers, the time window and the floors are constants (a test asserts the weights sum to 100 and that the floors cannot be reached below 75 by any combination). Lowering the threshold to its bound of 75 still cannot produce an automatic match without the reference, because the floors do not depend on it.

## The flows and their exact ledger postings

Accounts (fixed codes, per tenant, created idempotently): `1000` Mobile money clearing (ASSET), `1100` Accounts receivable (ASSET), `2100` Customer credit (LIABILITY), `4000` Sales revenue (REVENUE). Amounts are integers in minor units. A transaction has two or three entries, every entry greater than zero, debits equal credits.

| Flow | Trigger | Match row | Ledger transaction (`kind`, `ref`) | Entries | Status changes |
|---|---|---|---|---|---|
| **Order accepted** | order becomes payable (`PENDING_PAYMENT`) | none | `ORDER_ACCEPTED`, order id | Dr 1100 `T`; Cr 4000 `T` | none |
| **Auto** (score at least auto, all floors) | payment or order recorded | `AUTO`, amount = payment, excess 0 | `PAYMENT_MATCHED`, match id | Dr 1000 `P`; Cr 1100 `P` | order `PAID`, payment `MATCHED` |
| **Suggested** (score between the thresholds, or any floor missing) | same | `SUGGESTED`, no amount applied yet | **none** | none | **none** (a suggestion changes nothing) |
| **Confirm a suggestion** (seller; stop 3 entry point, same atomic step) | seller | row becomes `CONFIRMED` with the amounts computed **at confirmation**, from the locked balance | as auto | as the case below | as the case below |
| **Partial** (payment `P` below balance `B`) | confirm or manual | applied `P`, excess 0 | `PAYMENT_MATCHED` | Dr 1000 `P`; Cr 1100 `P` | order `PARTIALLY_PAID`, payment `MATCHED` |
| **Over-payment** (`P` above balance `B`, `B` greater than 0) | confirm or manual | applied `B`, excess `P - B` | `PAYMENT_MATCHED` | Dr 1000 `P`; Cr 1100 `B`; Cr 2100 `P - B` | order `PAID`, payment `MATCHED`; the excess is recorded on the match (`excess_minor`) |
| **Exact on the last balance** | any | applied `P` | `PAYMENT_MATCHED` | Dr 1000 `P`; Cr 1100 `P` | order `PAID` |
| **Unmatch** (stop 3) | seller | `REVERSED` | `REVERSAL` of the match's transaction | the mirror: Cr 1000, Dr 1100, Dr 2100 | order recomputed from its remaining matches; payment back to `RECEIVED` (or `UNVERIFIED` if it has no transaction id) |
| **Reject a suggestion** | seller | `REJECTED` | none | none | none |

Worked examples: order 5,000, payment 5,000 gives Dr 1000 5,000, Cr 1100 5,000. Order 5,000 already paid 2,000, payment 4,000 gives Dr 1000 4,000, Cr 1100 3,000, Cr 2100 1,000. A payment that arrives when the balance is 0 is refused (not an over-payment of nothing).

An order's status is a **function of its active matches** (sum of applied amounts): 0 gives `PENDING_PAYMENT`, between 0 and the total `PARTIALLY_PAID`, equal to the total `PAID`. A payment is `MATCHED` exactly when it has one active match. The database checks both at commit (below). `OVERPAID` is not a status: it is `excess_minor` on the match, as requirement 3.6 says "record the excess as OVERPAID with the amount".

## Statuses and the separate, named UPDATE grant

Today the application role cannot change a status. V016 grants, as three explicit statements and with **column lists**:

- `GRANT UPDATE (status) ON orders TO chatledger_app`
- `GRANT UPDATE (status) ON payment TO chatledger_app`
- `GRANT INSERT ON match TO chatledger_app` and `GRANT UPDATE (state, decided_by, decided_at, amount_minor, excess_minor, ledger_txn_id) ON match TO chatledger_app`

No DELETE, no TRUNCATE, no UPDATE of any other column. The amount and ledger columns are in the grant only because a suggestion is created without amounts and gets them at confirmation; a trigger lets them change **only** in the `SUGGESTED -> CONFIRMED` transition and never afterwards.

Two more walls, both in the database:

- **Transition triggers** (`BEFORE UPDATE OF status`). Orders: `PENDING_PAYMENT <-> PARTIALLY_PAID <-> PAID` and `PAID -> PENDING_PAYMENT`, nothing else; the app role can never touch `NEEDS_REVIEW`, `UNPARSED` or `CANCELLED` (later tasks add their own named transitions). Payments: `RECEIVED` or `UNVERIFIED -> MATCHED`, `MATCHED ->` back to `RECEIVED` only if it has a transaction id (`UNVERIFIED` only if it has none), and `RECEIVED <-> UNMATCHED` (seller action, stop 3). `DUPLICATE` and `UNPARSED` payments never change status.
- **Consistency at commit** (deferred constraint triggers, same pattern as the ledger balance check): order status equals the function of its active matches; a payment is `MATCHED` iff it has an active match; an active match has a posting whose debits equal `amount_minor + excess_minor`.

Tests: for every column of `orders`, `payment` and `match` other than the granted ones, an UPDATE as the application role fails with permission denied (generated from the catalog so a new column is covered); `has_column_privilege` is asserted for each granted and ungranted column; the amount columns cannot change outside the confirmation transition; an illegal transition is refused; UPDATE of `tenant_id` is refused.

## The `match` table and its invariants

New columns: `excess_minor BIGINT NOT NULL DEFAULT 0`, `ledger_txn_id UUID` (same-tenant foreign key), `created_at`. `amount_minor` becomes nullable **only for `SUGGESTED` and `REJECTED`** (CHECK tied to the state, written with explicit `IS NOT NULL`, the lesson from tasks 4 and 5). Further constraints:

- amounts: `amount_minor` between 1 and 10^12, `excess_minor` between 0 and 10^12, `score` between 0 and 100 (already a SMALLINT), `reasons_json` length and character set, state-specific CHECKs (`AUTO/CONFIRMED` need amounts and a ledger transaction; `AUTO` needs `decided_by IS NULL`; `CONFIRMED` needs `decided_by` and `decided_at`).
- **One payment cannot be matched twice:** `UNIQUE (payment_id) WHERE state IN ('AUTO','CONFIRMED')`.
- **No duplicate proposals:** `UNIQUE (order_id, payment_id) WHERE state IN ('SUGGESTED','AUTO','CONFIRMED')`. A `REJECTED` pair is remembered and not suggested again by the engine; a seller can still match it by hand.
- **Never more than the order total:** a deferred constraint trigger locks the order row, sums the active matches and requires the sum to be at most the total.
- **Never more than the payment:** for an active match, `amount_minor + excess_minor` must equal the payment amount (a payment is consumed whole by its one active match). The same trigger checks currency, and that `AUTO` has a transaction id on the payment and a payment status that is not `DUPLICATE`, `UNVERIFIED` or `UNPARSED`.
- **One posting per business event:** a partial unique index on `ledger_txn (tenant_id, kind, ref_id) WHERE reverses_txn_id IS NULL AND kind IN ('ORDER_ACCEPTED','PAYMENT_MATCHED')`. The ledger itself stays as built (append-only, balanced, one reversal per transaction).

## Atomicity: how a posting, a match and a status change commit together

`MatchingService.apply` is the only place that creates an active match, and it is **one database transaction** through `TenantScope` (the same mechanism as ingestion):

1. Lock the payment row (`SELECT ... FOR UPDATE`), then the order row. **Always payment first, then order**, in every operation, so two operations cannot lock in opposite orders (no deadlock). Lock the match row too when one exists.
2. Re-read everything inside the lock: statuses, amounts, currency, the order's active matches. The scores computed before the lock are advisory; **the commit step re-checks the floors and the balance on locked data**. If an automatic match no longer qualifies it becomes a suggestion; if the payment is no longer free or the balance is 0, nothing is written and the caller is told `NOT_APPLICABLE`.
3. Compute `applied = min(P, B)` and `excess = P - applied` from the locked balance (`B = total - sum of active matches`, computed, never a stored balance that could drift).
4. Ensure the four accounts exist (idempotent insert).
5. Insert the match, the ledger transaction and its entries (`appendWithin`), update the order status and the payment status, mark the other open suggestions for this payment or this (now paid) order as `REJECTED` with `decided_by` NULL ("superseded by the system"), append the audit row (`MATCH_AUTO` or `MATCH_CONFIRMED`, subject = match id).
6. Commit. The deferred triggers run now: the ledger balance, the match sums, the status consistency, the match-to-posting amounts.

**What a failed half-way run leaves behind: nothing.** Any exception, any trigger violation or a crash before commit rolls back the whole transaction: no match row, no ledger rows, no status change, no audit row. The payment is still `RECEIVED` and the order still open, so the next run retries; because of the unique indexes a retry can never double-post. The only trace of a failure is one log line with ids and a code. A crash **after** commit and before the caller hears about it is also safe: the state is complete and consistent. What can be lost is only the **trigger** (the "please match this" call after ingestion) if the process dies between ingestion and matching; nothing is wrong in the data, the payment simply waits unmatched (weakened defense 6).

**Concurrency.** Two operations on the same payment serialise on the payment lock; the second finds it `MATCHED` and writes nothing. Two payments on the same order serialise on the order lock; the second recomputes the balance. The unique indexes and the deferred triggers are the backstop if code is ever wrong. Tests: 16 threads trying to match one payment to different orders (one winner); 16 payments into one order with a balance smaller than their sum (the sum of applied amounts never exceeds the total, the ledger balances, the trial balance is 0); the same message ingested and matched twice; mutation checks remove each lock and each index.

## Unmatch and reversal (stop 3, designed now)

Only a SELLER (`REVERSE_TRANSACTION`). One transaction, same lock order: lock payment, order, match; require the match `AUTO` or `CONFIRMED`; post the **reversal** of its ledger transaction (`reverseWithin`; the mirror entries, including the excess credit; the unique reversal index makes a second unmatch fail); set the match `REVERSED` with who and when; recompute the order status from the remaining active matches; return the payment to `RECEIVED` (or `UNVERIFIED`); audit `MATCH_REVERSED`. A reversed pair can be matched again (a new match row). Nothing is ever edited or deleted.

## What happens to each kind of record

| Record | Engine | Auto | Suggest | Seller confirm | Ledger |
|---|---|---|---|---|---|
| Payment `RECEIVED` | candidate | yes, if all floors | yes | yes | posts on match |
| Payment `UNVERIFIED` | candidate | **never** | yes, score capped below auto | yes (seller's decision; marked in the reasons) | posts only after the seller's confirmation |
| Payment `DUPLICATE` | **never** a candidate | no | no | **refused** by service and by trigger | **never posted** (requirement 2.4) |
| Payment `UNPARSED` | never | no | no | no (nothing was read) | no |
| Payment `MATCHED` | not a candidate | no | no | no | already posted |
| Order `PENDING_PAYMENT`, `PARTIALLY_PAID` | candidate | yes | yes | yes | balance from active matches |
| Order `NEEDS_REVIEW` | not a candidate (no known total) | no | no | **refused** until the total is known (task 8 edits) | not posted |
| Order `UNPARSED`, `CANCELLED`, `PAID` | never | no | no | no | no |

## Where untrusted text reaches scoring, and the defense for each

Scoring reads parsed, validated **fields**, never `raw_text` (a source scan test fails if a matching statement selects `raw_text`). Three places carry text from outside:

1. **Sender name versus customer name** (both free text, bounded at 40 and 100 characters). Defense: both go through `TextKeys` (accent-insensitive, lower case); at most 8 tokens of at most 40 characters each are compared; comparison is `equals` or `startsWith` on tokens, so work is bounded and there is no regular expression, no similarity library, no reflection; the neutral name "Unknown customer" scores 0; the output is an integer, never the name; the name signal is capped at 12 and cannot reach auto alone.
2. **Reference versus order code** (both match `[A-Za-z0-9._:-]{1,64}` by CHECK). Defense: `ReferenceKeys.key` equality only (never `LIKE`, never a pattern); the uniqueness count among open orders is a prepared statement with a bound parameter; an ambiguous code scores 0.
3. **Sender number versus customer hash.** Not text at this point: both are keyed hashes under the per-tenant key; the comparison is string equality on 64 hex characters; the last four digits are four digits (CHECK).

Everything that leaves scoring is codes and integers. The reasons are rendered with `th:text` from codes, never from stored text. No log line, audit row, reason or exception carries a name, number, reference or transaction id.

## What I will not build in task 6

Suspicion flags and their veto on auto-match (task 7; I add a line to task 7 so an open flag blocks an automatic match); the board, the confirm button and any HTTP endpoint (task 8; the service is the entry point as in tasks 4 and 5); applying a customer's credit to a new order or refunding it; cancelling an order and reversing its `ORDER_ACCEPTED` posting; splitting one payment across several orders; matching across currencies; fuzzy amounts; machine learning; changing weights at runtime.

## Weakened defenses and trade-offs, each with its compensation

1. **A forged confirmation can reach an automatic match.** Every signal can be written by someone who writes the SMS. *Compensation:* the eight floors (transaction id, exact amount, unique reference, identity, tie margin, trusted recorder), no movement of money, the kill switch, audit, reversal by the seller, the task 7 veto, and `UNVERIFIED` never automatic.
2. **Order codes are not secret.** Sequential or guessable codes can be written by anyone. *Compensation:* ambiguity scores 0; the floors; a recommendation (not built here) that codes include a short random part.
3. **New UPDATE and INSERT privileges for the application role** on `orders.status`, `payment.status` and `match`. *Compensation:* column lists, no DELETE, transition triggers, deferred consistency triggers, row-level security, a catalog-driven test that every other column stays immutable.
4. **A seller can confirm anything**, including an `UNVERIFIED` payment. *Compensation:* by design the seller has the last word; confirmation is audited with who and when; SELLER only; unmatch is possible.
5. **A HELPER-recorded payment is never matched automatically** (if D5 is accepted). *Compensation:* it is suggested; the cost is slower matching when a helper records.
6. **Matching is triggered after commit and is best effort.** A crash between ingestion and matching leaves an unmatched payment until the next event or an explicit rematch (stop 3). *Compensation:* nothing is inconsistent, the payment stays visible as unmatched, the engine is idempotent.
7. **Candidate cap of 200 and at most 3 stored suggestions per payment or order.** Requirement 3.1 says "every open candidate". *Compensation:* most recent first, named constants within bounds, manual match for the rest; growth is bounded by the ingestion rate limit.
8. **Name similarity is a heuristic.** Two people with the same first name and initial look alike. *Compensation:* capped at 12 points, never alone, advisory.
9. **Thresholds are configuration.** A bad value could weaken auto-matching. *Compensation:* hard bounds, startup refusal, floors independent of thresholds, kill switch.
10. **The match-to-posting amount rule is enforced by a trigger and by tests, not by the ledger itself,** which knows nothing about matches. *Compensation:* the deferred trigger at commit, a reconciliation query (accounts receivable equals order totals minus active matches) used in the property tests.
11. **`appendWithin` bypasses `LedgerService`'s own call path.** *Compensation:* the account-tenant and overflow checks move into one shared helper used by both; amounts are capped at 10^12; the same tests run on both paths.
12. **Chart of accounts is created lazily by the application role,** which already holds INSERT on `account`. *Compensation:* fixed codes in code, unique per tenant; a pre-existing privilege, not a new one.
13. **Over-payment credit (2100) is a liability nothing consumes yet.** *Compensation:* recorded and visible; applying or refunding credit is a later, separately designed feature.
14. **`ORDER_ACCEPTED` is posted at ingestion** (if D1 is accepted), which changes the merged task 4 service. *Compensation:* the posting is idempotent; matching also ensures it before its first match; tests for both paths.
15. **Revenue is recognised when the order is accepted, not when it is paid.** This follows the design's posting rules. *Compensation:* named; the receivable shows what is unpaid.
16. **Row locks serialise matching per order and per payment.** *Compensation:* ordered locking, small transactions, tests under 16 threads.
17. **Time signals rely on a claimed time and the server clock.** *Compensation:* 10 points, plausibility window already enforced.
18. **Single currency per tenant.** *Compensation:* a different currency is never a candidate.
19. **Inherited:** register items 1, 5 and 31.

## Decisions I need from you, each with my recommendation

| # | Decision | Recommendation | Reason |
|---|---|---|---|
| D1 | **Where `ORDER_ACCEPTED` is posted.** A: inside the order ingestion transaction when the order is `PENDING_PAYMENT` (touches the merged task 4 service) and again, idempotently, before the first match. B: only at the first match. C: a separate job. | **A**, with the "ensure before first match" safety net. | The receivable must show unpaid orders (requirement 4); B understates receivables and revenue. The unique index prevents a double posting. |
| D2 | **Chart of accounts.** A: lazy idempotent creation inside the matching transaction. B: change `provision_tenant` (a `SECURITY DEFINER` function) and backfill. | **A.** | Does not touch a definer function; INSERT on `account` is already granted. |
| D3 | **Weights and tiers.** Amount 50 exact, 20 partial or over (up to 2x); reference 25; identity 15 (phone hash 15, name 12 or 8, +3 for last four); time 10. The design's table lists "name similarity 15"; I add the phone hash and the amount tiers (gap 4). | **Yes.** | Phone hash is a stronger identity than a name and is already stored; tiers make 3.5 and 3.6 reachable. |
| D4 | **Automatic only for an exact amount;** partial and over-payment always need the seller. And the eight floors above. | **Yes.** | Requirement 3.5 and 3.6 do not ask for automatic partial or over matches; the safe failure is a suggestion. |
| D5 | **Who may cause an automatic match.** Only payments recorded by a SELLER or the machine feed; a HELPER's payment is suggested. | **Yes.** | HELPER is the lower-trust role; the cost is one tap by the seller. Needs the caller's role passed to the matcher. |
| D6 | **Thresholds and bounds** as in the table, kill switch default **on**. Or default off until task 7 supplies flags. | **On.** | The floors are strong and every match is reversible; off would make requirement 3.2 unmet. Say if you prefer off until task 7. |
| D7 | **A payment is consumed whole by one active match** (`amount + excess = payment`); several payments can pay one order, one payment cannot pay several orders. | **Yes.** | Simple, checkable invariant; splitting is out of scope. |
| D8 | **Grants:** `UPDATE (status)` on `orders` and `payment`; `INSERT` and a six-column `UPDATE` on `match`; transition and consistency triggers. | **Yes.** | Minimum that works; every other column stays immutable and tested. |
| D9 | **Ledger atomicity** through `appendWithin` and a deferred match-to-posting trigger. | **Yes.** | The only way a posting, a match and a status change commit together. |
| D10 | **How matching starts.** A: a post-commit call from `OrderIngestionService` and `PaymentIngestionService` (small change to merged code, failure never loses the record). B: the callers (paste form, API) call it. | **A,** plus an explicit `rematch(tenant)` in stop 3. | B can be forgotten by a later endpoint; A cannot. |
| D11 | **`UNMATCHED`** is set only by the seller in stop 3; the engine leaves a payment `RECEIVED`. | **Yes.** | No engine-written status that means "looked at and gave up". |
| D12 | **`NEEDS_REVIEW` orders** cannot be matched until their total is known; **`UNVERIFIED` payments** can be suggested and confirmed by a SELLER but never automatic. | **Yes.** | No balance means no safe amount. |
| D13 | **Rejected suggestions are remembered** and not suggested again by the engine. | **Yes.** | Stops the same wrong proposal from returning. |
| D14 | **Caps:** 200 candidates, 3 suggestions. | **Yes.** | Bounded work and rows. |
| D15 | **Reasons are stored as codes** and rendered by the page. | **Yes.** | No stored text to escape or leak. |
| D16 | **Over-payment credit** is recorded and not consumed in this task. | **Yes.** | A separate feature with its own design. |
| D17 | **A line in task 7** that an open flag blocks an automatic match, and in task 8 that confirm and unmatch buttons use CSRF and the two permissions. | **Yes.** | So neither is forgotten. |

If you say "go" without changes I take my recommendation on all seventeen.

## Guards I will mutation-check

Each signal and tier; the zero for an ambiguous reference; each of the eight floors; the tie margin; the threshold bounds and the relation between them; the kill switch; the candidate exclusions (each status); the cap; the payment-then-order lock order; `FOR UPDATE` on each row; re-check inside the lock; `min(P, B)` and the excess; each posting amount and account (all six entries across the flows); atomicity (an injected failure after each step leaves nothing); the unique indexes (active payment, open pair, one posting per event); the sum trigger (order total, payment amount); the status transition trigger (each forbidden move); the status-consistency and match-to-posting triggers; every column of the grant (UPDATE granted on an extra column, DELETE granted); the amount columns frozen outside confirmation; `DUPLICATE` refused; `UNVERIFIED` never automatic; HELPER suggestion-only; the owner-run tenant predicate of every `MatchRepository` statement; reasons as codes (a name in a reason fails); `raw_text` never selected; the structural scan from tasks 4 and 5 over the matching code (no pattern, no format string, no reflection).

## Tests I will write (summary)

- **Unit (no Docker):** scorer (each signal, each tier, every floor, the table of example pairs with their expected score and reasons), name similarity (accents, initials, hostile and over-long names), config bounds, weights sum, hostile text corpus through scoring, reasons contain only codes.
- **Integration (PostgreSQL):** every flow with its exact ledger entries and trial balance; partial then over-payment on one order; refusal of `DUPLICATE`, `UNPARSED`, `NEEDS_REVIEW`; 16-thread tests (one payment, many orders; many payments, one order; the same event twice); rollback at each step; grants and the catalog-driven immutable-column test; transition and consistency triggers; owner-run scoping for `MatchRepository`; cross-tenant isolation; render escaping of a reason page; log canary.
- **Property-based (stop 3, jqwik):** random sequences of payments, orders, confirms and unmatches never assign more than a payment or an order total, never leave the ledger unbalanced, keep receivables equal to totals minus active matches, and leave statuses consistent.
- The unit phase stays green with Docker stopped.

## How the three stops split

- **Stop 2 (after your go):** 6.1 scoring with reasons and configured thresholds; 6.2 the auto, suggested, partial and over-payment flows with ledger postings, V016, `appendWithin`, `MatchingService.apply`, the engine runs and the post-commit hook, the `ORDER_ACCEPTED` posting; tests and mutation checks; summary.
- **Stop 3 (after your next go):** 6.3 confirm, reject, manual match, unmatch with audit of who and when, `rematch`, `UNMATCHED`; 6.4 the property-based tests; tick `tasks.md`; summary.

**Est. Credits Used:** not visible to me from inside the session; read it from the Kiro status bar.
