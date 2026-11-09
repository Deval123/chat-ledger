# Task 7 design stop: suspicion rules

Status: **approved by the owner on D1 to D13, with D4 changed: R4 (`SHORT_OF_ROUND`) is dropped, so there are ten rules.** Implemented in stop 2. Four clarifications from the owner are part of the design:

1. **Scope of D3:** a payment flag with no related order blocks every pair of that payment; an order flag blocks every pair of that order; a pair flag (a payment flag with a related order) blocks only that pair. Tested, including "a pair flag on (P,A) does not block (P,B)" and "a payment-only flag blocks (P,B)".
2. When `applyWithin` writes a pair finding and degrades to a suggestion, **the finding and the suggestion commit together** (one transaction). Tested (the finding is still there after the degrade) and mutation-checked (the finding written in a transaction that is rolled back).
3. `docs/kiro-notes.md` states that a payment and its flags share one transaction on purpose, and that a retry is safe because ingestion is idempotent by (tenant, external id).
4. A `tasks.md` line under task 9: the demo seed triggers at least R1, R5, R8 and R10 or R11.

What was built, and where it differs from this text, is in `docs/kiro-notes.md` ("Task 7") and the design register. The proposal below is kept as written, except that R4 is removed.
Branch: `task-7-suspicion` (no commit, no new branch).

## What I read, and what it changes

- **Requirement 5** lists six cases (duplicate transaction id; `UNVERIFIED`; an amount off by a deliberate-looking margin such as a missing digit; a sender name unlike the customer's; a time before the order or far outside the window; the same sender under a different name on a recent order). Flags are advisory: the seller decides.
- **Task 6 design stop, D17:** an open flag must block an automatic match, and the flagged pair is suggested instead. This is a `tasks.md` line under task 7.
- **The `flag` table (V006)** has `id, tenant_id, subject_type, subject_id, code, reason, created_at, resolved_at`. The application role has **SELECT only** (V008), row-level security (V011), and `idx_flag_open_subject (tenant_id, subject_id) WHERE resolved_at IS NULL` (V018). There is **no foreign key** on the subject (it is polymorphic), **no CHECK** on `code`, `subject_type` or `reason`, **no column for who resolved**, and **no uniqueness**, so nothing makes a rule idempotent today. Nothing writes the table yet.
- **The `suspicion` module** has `FlagCode` (six codes from the design: `DUPLICATE_TX, NO_PROVIDER_ID, AMOUNT_NEAR_MISS, NAME_MISMATCH, TIME_ANOMALY, SENDER_ALIAS`), a `SuspicionFlag` record that carries ids and times, and a `SuspicionRule<C>` interface returning `List<SuspicionFlag>`. There are no rules. It depends on `matching`, and `web` depends on it.
- **`ReasonCode` / `FloorCode`.** `MatchScorer` is automatic only when **every** `FloorCode` is met (`floors.size() == FloorCode.values().length`) and the score reaches the threshold. A new floor is therefore required for an automatic match without any other code change. `MatchingService.applyWithin` re-scores on the locked rows for `Mode.AUTO` and degrades to a suggestion (`AUTO_NOT_ELIGIBLE`) if the pair is no longer eligible.
- **Ingestion.** `PaymentValidator` already refuses a time more than 15 minutes ahead or more than 35 days back (`PaymentLimits.MAX_FUTURE`, `MAX_AGE`): such a payment is `UNPARSED`, not a flagged payment. A repeated transaction id is already stored as `DUPLICATE` linked to the first, and a template without a transaction id gives `UNVERIFIED`. The payment insert and the matcher run in separate transactions (the matcher runs after the commit).
- **Customers.** A customer is found or created by phone hash with `ON CONFLICT DO NOTHING`. So **a second order from the same phone under a different name silently gets the first name**, and the new name survives only in the raw text. That is exactly requirement 5.6, and today it is invisible.
- **The board (task 8).** Flagged = any open `flag` row on the subject, **or** a payment in status `DUPLICATE` or `UNVERIFIED`; the column carries a "partial until the suspicion rules" sentence. Detail pages list open flags (`code`, `reason`, time). `Permission.RESOLVE_FLAG` exists (seller only) but no route uses it, so there is no resolve button.

## 1. The rules

One class per rule in `dev.chatledger.suspicion`, pure (no I/O, no clock, no regular expression), each returning zero or more **findings**: a code, a subject (`PAYMENT` or `ORDER`), optionally a related order, and nothing else. **No text from a message enters a finding.** The plain sentence comes from the code (`messages.properties`), and the specific values (two last-fours, two amounts, two times) are shown from the records at view time. The current `SuspicionRule` / `SuspicionFlag` are replaced by `Rule<C>` returning `List<Finding>`; the repository adds ids and times.

Three kinds of input, so each rule sees only what it needs:

- **payment rules** (`PaymentContext`): the new payment's parsed facts, its status, the time it is recorded, the count of recent payments from the same phone, and the names those used;
- **order rules** (`OrderContext`): the new order's parsed name, its phone hash, and the existing customer for that phone;
- **pair rules** (`PairContext`): `OrderFacts` and `PaymentFacts` exactly as the scorer sees them.

| # | Code | Kind | Trigger | Plain reason (shown to the seller) |
|---|---|---|---|---|
| R1 | `DUPLICATE_TX` | payment | The transaction id was already recorded for this workspace and provider (the payment is `DUPLICATE`) | "This transaction id was already recorded. The same confirmation may have been sent twice." |
| R2 | `NO_PROVIDER_ID` | payment | No transaction id (`UNVERIFIED`: a forward or a screenshot) | "This confirmation has no transaction id. Check your provider balance before you hand over the goods." |
| R3 | `AMOUNT_NEAR_MISS` | pair | The amount is the order's balance with **one digit missing**, or with **two neighbouring digits swapped** | "The amount looks like the order total with a digit missing or swapped." |
| R5 | `PAID_BEFORE_ORDER` | pair | The payment time is earlier than the order's creation by more than `before-order-tolerance` (default 60 min) | "This payment was made before the order existed." |
| R6 | `SENDER_BURST` | payment | At least `burst-count` (default 4) payments from the same sender phone within `burst-window` (default 30 min), this one included | "Many payments came from this sender in a short time." |
| R7 | `PHONE_MISMATCH` | pair | Both sides have a phone and they differ (by hash; by last four if only last fours are known) | "The payment came from a different phone number than the one on the order." |
| R8 | `NAME_MISMATCH` | pair | Both names are known, the phones are not equal, and `NameSimilarity` finds no agreement | "The sender's name does not resemble the customer's name." |
| R9 | `PAID_OUTSIDE_USUAL_WINDOW` | payment | The payment time is more than `usual-age` (default 3 days) before it was recorded, or more than `usual-ahead` (default 2 min) after | "The payment time is far from when it was recorded." |
| R10 | `SENDER_ALIAS` | order and payment | Order: the phone belongs to an existing customer whose name does not agree with the name in this order. Payment: the same sender phone used a name that does not agree within `alias-lookback` (default 90 days) | "This phone number was used before under a different name." |
| R11 | `OVERPAYMENT` (mine) | pair | The payment exceeds the balance by at least `overpay-min` (default 10% of the balance) | "The payment is more than the order. A common trick is to overpay on purpose and ask for the difference back. Check your balance before you send anything." |

`TIME_ANOMALY` from the design is split into R5 and R9 (two different questions, two sentences). Every requirement-5 case is covered: 5.1 is R1, 5.2 is R2, 5.3 is R3, 5.4 is R8 (plus R7), 5.5 is R5 and R9, and 5.6 is R10.

**Why R11.** The overpay-and-refund scam is the most common mobile-money fraud aimed at sellers: a forged or real-but-reversed overpayment, then "please send back the difference". The engine already records the excess as customer credit without warning anyone.


### Example tables (each becomes a unit-test table; amounts in minor units, scale 0)

**R1 `DUPLICATE_TX`**

| Payment | Flag? |
|---|---|
| status `DUPLICATE`, `duplicate_of` set | yes |
| status `RECEIVED` | no |
| status `UNPARSED` | no |

**R2 `NO_PROVIDER_ID`**

| Payment | Flag? |
|---|---|
| `UNVERIFIED` (forwarded shape) | yes |
| `RECEIVED` with a transaction id | no |
| `DUPLICATE` (has an id; R1 covers it) | no |

**R3 `AMOUNT_NEAR_MISS`** (balance, then paid)

| Balance | Paid | Flag? | Why |
|---|---|---|---|
| 50000 | 5000 | yes | a zero dropped |
| 12500 | 1250 | yes | a digit dropped |
| 15000 | 1500 | yes | |
| 4500 | 5400 | yes | neighbours swapped (over, so R11 fires too) |
| 4500 | 4050 | yes | neighbours swapped |
| 5000 | 3000 | no | a substituted digit is an ordinary part payment, not a slip |
| 5000 | 5000 | no | exact |
| 5000 | 500000 | no | an added digit is out of R3's scope (R11 covers it) |
| 100 | 10 | no | below `near-miss-min` (default 1000): too small to matter, too common |

**R5 `PAID_BEFORE_ORDER`** (tolerance 60 min)

| Order created | Paid at | Flag? |
|---|---|---|
| 12:00 | 09:30 | yes |
| 12:00 | 10:59 | yes |
| 12:00 | 11:00 | no (exactly the tolerance) |
| 12:00 | 11:45 | no (the customer paid, then typed the order) |
| 12:00 | 12:05 | no |
| 12:00 | (no time) | no (`UNPARSED` never reaches here) |

**R6 `SENDER_BURST`** (4 in 30 min, same phone hash)

| Earlier payments from this phone | Flag on the new one? |
|---|---|
| 3 in the last 20 min | yes |
| 3, the oldest 31 min ago | no |
| 2 in the last 5 min | no |
| 10 from another phone | no |
| this payment has no phone (forward with last four only) | no (named weakness, below) |

**R7 `PHONE_MISMATCH`**

| Order phone | Sender phone | Flag? |
|---|---|---|
| hash A | hash B | yes |
| hash A | hash A | no |
| last four 1234 only | last four 5678 only | yes |
| hash A | none | no |
| none | hash B | no |

**R8 `NAME_MISMATCH`**

| Customer | Sender | Phones | Flag? |
|---|---|---|---|
| Ana Kamga | Cy Synthetic | differ or unknown | yes |
| Ana Kamga | A. Kamga | | no (`INITIAL`) |
| Ana Kamga | Kamga Ana | | no (`FULL`) |
| Ana Kamga | Bob Synthetic | **equal hashes** | no (same phone: a nickname, not a stranger) |
| Unknown customer (neutral) | Bob Synthetic | | no (no name to compare) |
| Ana Kamga | (none) | | no |

**R9 `PAID_OUTSIDE_USUAL_WINDOW`** (recorded at 12:00 on day 10; usual age 3 days, ahead 2 min)

| Paid at | Flag? |
|---|---|
| day 6 | yes |
| day 7, 11:59 | yes |
| day 7, 12:00 | no |
| day 10, 12:01 | no |
| day 10, 12:03 | yes (ahead, inside the hard 15 min) |
| day 10, 12:20 | (never stored as payable: `UNPARSED` by the hard window) |

**R10 `SENDER_ALIAS`**

| Case | Flag? |
|---|---|
| order: phone of customer "Ana Kamga", this order names "Bob Synthetic" | yes, on the order |
| order: same phone, "A. Kamga" | no |
| order: same phone, no name in the order | no |
| order: a new phone | no |
| order: the customer is the neutral "Unknown customer" | no |
| payment: same sender phone used "Bob Synthetic" 10 days ago, now "Ana Kamga" | yes, on the payment |
| payment: same name 100 days ago | no (beyond the lookback) |

**R11 `OVERPAYMENT`** (min 10%)

| Balance | Paid | Flag? |
|---|---|---|
| 5000 | 5500 | yes |
| 5000 | 9000 | yes |
| 5000 | 5400 | no (8%) |
| 5000 | 5000 | no |
| 5000 | 20000 | yes (beyond twice the balance the scorer already ignores the pair; R11 is still evaluated on a suggestion or a manual match) |

## 2. Where rules run, how flags are written, how a seller resolves one

**Where.**

- **Payment rules (R1, R2, R6, R9, R10 payment side) run at ingestion, inside the same transaction that inserts the payment.** That way no other transaction can ever see the payment without its flags, and a concurrent rematch cannot match it in between. `PaymentRepository.insert` becomes `insertWithin` on a caller's connection; the two bounded reads (burst count, recent names) run there.
- **The order rule (R10 order side) runs inside the order insert transaction**, where the customer is already looked up by phone. The parsed name is compared before it is dropped by `ON CONFLICT`.
- **Pair rules (R3, R5, R7, R8, R11) run at matching**, on the pairs the engine is about to act on: the automatic candidate, inside `applyWithin` on the locked rows, and the stored suggestions (at most three). They also run on the seller's confirm and manual match, to **show** flags, never to refuse. Pair rules are pure functions of `OrderFacts` and `PaymentFacts`, which `applyWithin` already builds on the locked rows, so re-checking them costs no query.

**Writes (V019).**

- Grant `INSERT` on `flag` and `UPDATE (resolved_at, resolved_by)` only. No `DELETE`, no other column (the catalog-driven grant test is extended).
- New columns: `payment_id` and `order_id`, typed, with **composite foreign keys to the same tenant**, replacing the polymorphic `subject_id` (D2); `resolved_by` with a composite FK to `app_user`.
- CHECKs: `subject_type IN ('PAYMENT','ORDER')`; a `PAYMENT` subject has `payment_id` and may have `order_id` (a pair flag); an `ORDER` subject has `order_id` and no `payment_id`; `code` in the ten codes; `reason` 1 to 300 characters; `resolved_by IS NULL` exactly when `resolved_at IS NULL`.
- **Idempotent per rule and subject:** a unique index on `(tenant_id, code, subject_type, coalesce(payment_id, nil), coalesce(order_id, nil))`, with `INSERT ... ON CONFLICT DO NOTHING`. Re-running a rule (a retried request, a rematch) adds nothing.
- A trigger makes a flag **write-once except for its resolution**: every column is frozen, `resolved_at` may go from NULL to a value once and never back, for every role including the owner.
- `reason` stores the code's English sentence (fixed text, no message content), so an export or the audit is readable without the catalogue. The page renders from the code.
- Rows are tenant-scoped by RLS and by every statement's own predicate (owner-run isolation tests, as in task 8).
- **Backfill:** V019 inserts `DUPLICATE_TX` and `NO_PROVIDER_ID` flags for the existing `DUPLICATE` and `UNVERIFIED` payments, so the board can drop its status-derived rule and show only flag rows (D9).

**Resolving.** `POST /app/flags/{id}/resolve`:

- permission `RESOLVE_FLAG` (seller only), CSRF, the action limit, 404 for a foreign or unknown id;
- `FlagService.resolve` asks the database whether the user is an enabled seller of the tenant, then updates `WHERE id = ? AND resolved_at IS NULL` and writes the audit row `FLAG_RESOLVED` (subject = flag id; actor = the seller), all in one transaction;
- a second resolve gets a fixed sentence ("Already resolved");
- one click, no check page (D7): resolving changes no money and, per D3, does not unlock automatic matching;
- the button sits beside each open flag on the payment and order pages, and on the Flagged card: "I checked this";
- resolved flags stay on the detail page as history ("Checked by you, 03 Nov 11:02");
- there is no un-resolve, no bulk resolve, and no free-text note (no stored untrusted text).

## 3. The matcher

- A ninth floor, **`FloorCode.NOT_FLAGGED`**. `OrderFacts` and `PaymentFacts` gain a `flagged` boolean, read by the existing candidate queries with one `EXISTS` per side. The scorer adds the floor only when neither side is flagged and no pair rule fires on the pair. Because eligibility is "every floor met", **a flagged pair can never be planned as automatic**, and the explainer already lists missing floors ("A flag is open on this payment or order", "as of now").
- **The second wall, under the lock.** `applyWithin` with `Mode.AUTO`, after locking the payment and the order, reads the flag state for both, evaluates the pair rules on the locked facts, inserts any pair findings in the same transaction, and returns `DEGRADED_TO_SUGGESTION` with a new reason `FLAGGED` if anything is flagged. The engine then stores the pair as a suggestion (the existing path). The payment's own flags were committed with the payment, so there is no window.
- **The seller's confirm and manual match are not blocked** (flags are advisory, requirement 5). The card and the confirm button show the open flags in sentences.
- **D3, my recommendation, stronger than "open":** any flag on the payment or the order, **open or resolved**, keeps the pair suggestion-only. Resolving clears the Flagged column; it does not hand the decision back to the engine. That closes the "resolve it so it matches" path.
- **Tests: one per flag code.** For each code, a pair that would otherwise match automatically (shown by a control without the flag), with that flag present: the result is a suggestion with reason `FLAGGED`, no posting, no `AUTO` row. For pair codes the flag is created by the rule inside `applyWithin`; for payment and order codes it is created at ingestion. Plus: a resolved flag still blocks (D3), and a flag on another pair does not block this one.

## 4. Bounds

**Configuration** (`app.suspicion.*`, a record validated at startup like `SecurityProperties`; a bad value stops startup):

| Key | Default | Allowed |
|---|---|---|
| `<rule>.enabled` | true | (each rule) |
| `near-miss-min` | 1000 minor units | 1 to 10^12 |
| `before-order-tolerance` | 60 min | 10 min to 24 h |
| `burst-count` | 4 | 2 to 20 |
| `burst-window` | 30 min | 5 min to 24 h |
| `usual-age` | 3 days | 1 to 34 days (inside the hard 35) |
| `usual-ahead` | 2 min | 0 to 15 min (inside the hard 15) |
| `alias-lookback` | 90 days | 1 to 365 days |
| `overpay-min` | 10% | 1% to 100% |

Percentages are basis points (integers), never floats. A test proves that the default configuration enables every rule, and the startup log names any disabled rule (fixed label).

**Cost per payment**, all bounded:

- R1 and R2: none (already decided).
- R6: one indexed count, `SELECT count(*) FROM (SELECT 1 ... LIMIT burst-count)`.
- R10 payment side: at most 20 names, read by the same index.
- R10 order side: the existing customer lookup.
- Pair rules: pure, at most 1 + 3 pairs per engine run.
- Flag state: two `EXISTS` per pair.
- Inserts: at most 10 codes × 4 pairs per payment.

**Indexes** (V019):

- `idx_payment_sender_paid (tenant_id, sender_phone_hash, paid_at DESC) WHERE sender_phone_hash IS NOT NULL` (R6, R10);
- `idx_flag_payment (tenant_id, payment_id)` and `idx_flag_order (tenant_id, order_id)`, not partial, because D3 counts resolved flags too;
- the unique `uq_flag_rule_subject`;
- `idx_flag_open_subject` replaced by `(tenant_id, payment_id) WHERE resolved_at IS NULL` and `(tenant_id, order_id) WHERE resolved_at IS NULL` for the board;
- the existing `uq_customer_phone_per_tenant` for R10's order side.

## 5. Abuse and false positives

| Attack | Limit |
|---|---|
| **Flood flags** (a helper, a compromised feed, or a customer sending many messages) | Ingestion is limited per tenant (60/min, burst 30); flags are unique per rule, subject and pair; pair rules run on at most four pairs; a flag only **reduces** automation, never moves money or refuses the seller. The cost is the seller's attention, below |
| **Grief a real customer** by forging their phone in fake messages (R6, R10) | Their payments become suggestion-only; nothing is lost or refused; the sentence names the rule, not the customer |
| **Hide behind a resolved flag** | A flag is write-once and cannot be reopened or edited (trigger). New evidence makes a new row: another payment is another subject, and another order is another pair key. With D3, a resolved flag still keeps automation off, so resolving buys the attacker nothing. Resolutions are audited with who and when |
| **Make the seller ignore flags** (fatigue) | Few rules with tuned defaults; R5's tolerance accepts the common "paid, then typed the order". R8 does not fire when the phones agree. Every flag is one plain sentence with the two values side by side. No bulk resolve. Resolving does not unlock automation, so there is no incentive to click through. A noisy rule is switched off by the operator in configuration, not by the seller clicking. The Flagged count is on the summary strip |
| **Avoid the rules** (a fresh phone, an exact amount, a novel transaction id on a forged SMS) | **Not caught.** The rules cannot verify a payment with the provider; that is the signed machine feed (task 11). Named below |
| **Inject text** through a flag | No message text is stored in a flag; sentences are fixed; logs use codes and ids only |
| **Race the flags** (match before the flag exists) | Payment flags commit with the payment; pair flags are computed under the lock inside the automatic step |

## 6. Weakened defenses, scope, decisions, tests

### Weakened defenses and their compensation

1. **The application role gains `INSERT` and `UPDATE (resolved_at, resolved_by)` on `flag`.** *Compensation:* column-limited; a trigger freezes every other column and allows one NULL-to-value resolution; no `DELETE`; FKs pin the subject to the tenant; the grant test lists it.
2. **Flags are heuristics.** Every rule has false positives (family members pay with another phone, prepayment, nicknames) and false negatives (a forged SMS with a novel id passes every rule). *Compensation:* advisory only, the seller always confirms, every flag says why in one sentence, and the claim "payments are verified" is never made.
3. **R6 and R10 (payment side) need the sender's phone.** A forward with only the last four escapes both. *Compensation:* forwards are already `UNVERIFIED` (R2) and never automatic.
4. **Thresholds are global, not per seller.** *Compensation:* bounded configuration with startup checks; a per-seller tuning page is out of scope.
5. **With D3, a seller who resolved a flag still has to confirm the match by hand.** *Compensation:* one click on the suggestion; safer than letting a click re-enable automation.
6. **The stored `reason` duplicates the catalogue text in English.** *Compensation:* the page renders from the code; the column is for exports and audits.
7. **Rules are not re-run on old data** (except the backfill of R1 and R2). *Compensation:* a rematch evaluates pair rules on the pairs it touches.
8. **The flag state adds two `EXISTS` to every candidate query of the matcher.** *Compensation:* indexed; measured in an integration test on 1,000 candidates (an upper bound in milliseconds, unverified until stop 2).

### What I will not build in task 7

Provider verification (task 11); machine learning or scores across tenants; per-seller thresholds or a rules page; notifications; un-resolve, bulk resolve or notes on a resolution; flags on matches that already exist (rules look forward); re-running rules over history; customer credit refunds (R11 only warns).

### Decisions I need from you

| # | Decision | Recommendation | Reason |
|---|---|---|---|
| D1 | **The ten rules** above (R11 included; `TIME_ANOMALY` split into R5 and R9; R4 dropped by the owner) | **Yes.** | Covers requirement 5 and your list; R11 is the commonest scam aimed at sellers |
| D2 | **Typed subject columns** (`payment_id`, `order_id` with composite FKs), replacing the polymorphic `subject_id`; the board's two queries rewritten | **Yes.** | The database then refuses a flag on another tenant's or a missing record; the table has never been written, so nothing migrates |
| D3 | **Any flag, open or resolved, keeps the pair suggestion-only**; resolving only clears the Flagged column | **Yes.** | Removes the incentive to click through; the seller still has the last word by confirming |
| D4 | ~~R4 `SHORT_OF_ROUND` included~~ | **Dropped by the owner.** | It could not change a match |
| D5 | **Payment rules run inside the payment's insert transaction**, the order rule inside the order's; pair rules at matching, under the lock | **Yes.** | No window between the payment and its flags |
| D6 | **No message text in a flag**; fixed sentences from the code, values shown from the records | **Yes.** | No stored untrusted text, no log forging |
| D7 | **Resolve is one click** ("I checked this"), seller only, audited, write-once, no undo, no bulk | **Yes.** | It moves no money and, with D3, unlocks nothing |
| D8 | **Flags never refuse the seller's confirm or manual match**; they are shown on the card and the button | **Yes.** | Requirement 5: advisory |
| D9 | **Backfill R1 and R2 flags** for existing `DUPLICATE` and `UNVERIFIED` payments; the board shows Flagged from flag rows only. A `DUPLICATE` payment whose flag is resolved leaves Flagged for the "Set aside" list; an `UNVERIFIED` one for Review | **Yes.** | One source of truth; resolving has a visible effect |
| D10 | **Thresholds and defaults** as in section 4, bounded, startup-checked, each rule switchable by the operator | **Yes.** | No unbounded or nonsense setting |
| D11 | **R5 tolerance 60 minutes** (the scorer's time points use 10) | **Yes.** | Prepayment is common in chat commerce; 10 would flag it constantly |
| D12 | **New floor `NOT_FLAGGED`** plus the `FLAGGED` re-check in `applyWithin` | **Yes.** | Two walls; the explainer lists the reason without new page code |
| D13 | **Remove the board's "partial" note** and the status-derived Flagged rule once D9 lands | **Yes.** | No longer true |

### Test plan

- **Unit, Docker stopped:**
  - one test class per rule with its example table (positive and negative rows as above, plus the boundaries: exactly the tolerance, exactly the count, exactly the gap);
  - property tests for R3 (any balance with one digit removed flags, any one-digit substitution does not) and R6 (monotone in the count);
  - the configuration's bounds, each key below and above;
  - the default-enables-all test;
  - a source scan of `suspicion` (no regex, format, reflection, script or process, as in `matching`) and that rules take no clock or repository;
  - the scorer with the new floor (eligible only when not flagged);
  - every code has a sentence in `messages.properties`.
- **Integration:**
  - **V019:** grants (insert, two-column update, no delete), the write-once trigger for the app role and the owner, the CHECKs, composite FKs refusing another tenant's subject, idempotence under 16 concurrent inserts of one finding, the backfill.
  - **Ingestion:** flags are in the same transaction as the payment (a failure in the flag insert rolls back the payment), the R6 and R10 queries scoped and bounded.
  - **Matcher:** one test per flag code; a resolved flag still blocks; another pair's flag does not; the under-lock re-check with a flag written between planning and applying; confirm and manual match still allowed and showing the flags.
  - **Resolve route:** seller only, helper 403, foreign 404, missing or wrong CSRF 403, second resolve is a sentence, the audit row, the action limit.
  - **Board:** Flagged from rows only, resolved items move as in D9, hostile corpus through the flag pages.
  - **Isolation:** `FlagRepository` registered in `IsolationRegistry` with an owner-run test.
- **Mutation checks:**
  - each rule's comparison and boundary (`<` versus `<=`, each threshold off by one, each example class);
  - each configuration bound;
  - the new floor (removed, always met);
  - the under-lock re-check (removed, or not writing the finding);
  - D3 (open only instead of any);
  - each V019 CHECK, the trigger conditions, the unique key's columns, each FK;
  - the resolve permission, the seller check, the `resolved_at IS NULL` predicate, the audit row;
  - the ingestion transaction boundary;
  - the board's flag predicates;
  - the tenant predicate of every new statement.
  
  Survivors are fixed with a test or the guard is deleted and named, as in task 8.

### Unverified in this proposal

- The cost of the two `EXISTS` per candidate (section 4) is reasoned, not measured.
- The false-positive rates of the defaults are judgement, not data; there is no real traffic to tune on.
- That R11 is "the commonest" scam is general knowledge in the mobile-money context, not something I checked against a source for this proposal.

### Estimated credits

Not visible to me from inside the session (read it from the Kiro status bar). By scope I expect stop 2 to be about half of task 8's stop 2: one migration, ten small rule classes, one service, one route, and tests. That is a guess, not a measurement.

If you say "go" without changes I take my recommendation on all thirteen.
