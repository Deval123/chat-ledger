# Task 9 design stop: simulators and demo tenant

Status: **approved by the owner on D1 to D21, with D3 and D4 changed and D22 to D25 added** (section 7). Implemented in stop 2. Branch:
`task-9-demo` (nothing committed by me).

Owner's changes, verbatim in substance:
- D3/D4: a pool of 30 (bounds 10 to 50) and a lease of 30 minutes (bounds 10 minutes to 4 hours).
- D22: a visitor already signed in to a leased demo workspace who opens or posts `/demo` again is sent to that workspace and uses no new
  lease. Tested.
- D23: `app.security.trusted-proxy-hops` (0 to 2, default 0, checked at startup, named in the startup log) decides the client address of
  the claim limit. Task 13 sets it for the host.
- D24: `demo-refresh` stays a one-shot command, and also gets a loop mode (`demo-refresh --every 600s`, 60 s to 3600 s) that the container
  entrypoint starts as a separate process with the demo operator login, never inside the web process. One refresh at a time, by the
  advisory lock.
- D25: a size estimate for one workspace and for 30 days at the claim limits, marked unverified, with what the operator does when it does
  not fit.

Everything else is kept: nothing deleted, no trigger weakened, the same ingestion services for seed and scenarios, no text field on any
demo route, every guard mutation-checked, the scrub columns checked against their constraints in stop 2.

## What I read, and what it changes

- **Requirement 7.** 7.1 a chat simulator that looks like a WhatsApp conversation, and a confirmation feed. 7.2 seven one-click scenarios: happy
  path, underpayment, overpayment, duplicate confirmation, screenshot-only payment, wrong sender name, late payment. 7.3 synthetic data only,
  no real phone numbers.
- **tasks.md, task 9** (every line): timestamps inside the `PaymentLimits` window relative to now (at most 15 minutes ahead, at most 35 days
  back), in each template's own zone and date format, and a test that every generated payment is accepted; the seed exercises auto,
  suggested, partial, over-payment, duplicate, unverified and unparsed, with references and names that make the score work, and a test per
  outcome; the catalog (English and French names) is written by the seed or an operator command, never by the web application, and the demo
  orders compute their totals from it; the seed raises R1, R5, R8 and R10 or R11, and a test checks each flag.
- **Conflict to settle (D10).** tasks.md calls the seven matching flows "the seed" and requirement 7.2 names seven different scenarios. I
  propose the seed covers the tasks.md list and the flags, and the seven one-click scenarios are requirement 7.2's list. The mapping is in
  section 3.
- **Provisioning.** `provision-tenant` and `provision-user` are one-shot commands. `ChatLedgerApplication.main` sends any `provision-*`
  argument to `ProvisioningCommand` before Spring starts. They use plain JDBC with `PROVISION_DB_*` and call two `SECURITY DEFINER`
  functions (V013). The provisioner role has no table privileges.
  - `provision_tenant` hard-codes XAF and scale 0 and leaves `timezone` at `UTC`.
  - The application role cannot write `tenant`, `app_user` or `catalog_item`.
- **Phone hashing needs the application secret.** `PhoneKeys` derives each tenant's key as HMAC(`APP_SECRET`, purpose + tenant id). There
  is no key in the database. A raw-SQL seed could not write phone hashes that the application would match. **So the seed must run the real
  ingestion services, with the real secret.** That is also the "no back door" rule.
- **Recorder trust.** A payment is auto-matched only if its `PAYMENT_INGESTED` audit row names a SELLER of the tenant. A null actor, or a
  helper, makes it suggestion-only. So the simulator and the seed must record as the demo workspace's seller, or the happy path never
  auto-matches.
- **StartupGuards** checks only the session cookie (HttpOnly, SameSite strict, `Secure` outside `dev`, `__Host-` rules). `KeyedHasher`
  refuses a weak `APP_SECRET`. `SecurityProperties` has every rate limit with defaults:
  - login: 10/10m per IP, 5/15m per account, 30/10m for the login page per IP;
  - ingestion: 60/1m with a burst of 30 per tenant, shared by orders and payments;
  - actions: 120/1m per user;
  - rematch: 3/1m and 20/1h;
  - export: 6/10m.
- **Client IP.** It is `request.getRemoteAddr()`. Forwarded headers are not trusted until task 13 names the proxy, so behind a proxy every
  visitor shares one IP key.
- **Public paths today:** `/`, `/login`, `/error`, `/health`, `/actuator/health`, `/assets/**`. Everything else is `denyAll` or
  authenticated. `HandlerCoverageTest` lists the four public handlers. The catalog test requires RLS and a tenant policy on every table
  (allowlist: `flyway_schema_history`). A guard fails the build on an unlisted `SECURITY DEFINER` function.
- **Ingestion services.**
  - `OrderIngestionService.ingest` and `PaymentIngestionService.ingest(tenant, actor, externalId, text)`: input checks, the per-tenant
    limit, idempotency by external id, the pipeline, flags in the insert transaction, then the matcher after commit.
  - External ids allow `A-Z a-z 0-9 . _ : -`, up to 128 characters.
  - Orders are at most 4,000 characters; payments at most 1,000.
- **The five payment templates.** All five use XAF, zone `+01:00` and date format `dd/MM/yyyy HH:mm`:
  - `provider-a-received`;
  - `provider-a-received-ref` (`Ref {reference}`);
  - `provider-a-forwarded` (no transaction id, last four only: `UNVERIFIED`);
  - `provider-b-recu` (French);
  - `provider-b-recu-ref` (French, `Motif : {reference}`).
  
  They are proven at startup against a fixed clock (2026-11-03T12:00Z). Their examples are dated 02 to 03/11/2026, so they cannot be reused
  as demo text: real time moves on and they would fall outside the 35-day window.
- **Immutability.** `ledger_txn`, `ledger_entry` and `audit_log` refuse UPDATE, DELETE and TRUNCATE for every role that cannot alter
  triggers, the owner included. `flag` is write-once and never deleted (V019).
  - `orders`, `payment`, `match`, `customer` and `catalog_item` have no delete trigger, but flags and matches hold foreign keys to them.
  - **So a demo tenant cannot be emptied without weakening the triggers.** I do not propose that (section 4).
- **The board.** `GET /` redirects to `/app`, and an anonymous visitor lands on `/login`. The board shows the summary strip, the paste
  forms, the chips, five columns and a footer.
  - A judge who opens the app URL today sees a login form and nothing to log in with.
- **No scheduler** exists in the application (no `@Scheduled`), and none should run privileged work from the web role.

## 1. How a public visitor enters, and why it is not a hole

### The shape

**A demo is a separate deployment with its own database** (D1). The public demo instance never holds a real seller's data, and a real
instance never opens the demo door. Two switches, both off by default, must agree:

1. **Configuration:** `app.demo.enabled=false` by default. While false, the demo controllers are not registered (`@ConditionalOnProperty`)
   and `/demo` is not on the permit list, so every demo path answers 404 (tested).
2. **The database:** a one-row table `deployment_mode` (`REGULAR` by default, set by V020). Only the demo operator command `demo-init` sets
   it to `DEMO`, and it refuses if any regular tenant exists.
   - In `DEMO` mode, `provision_tenant` refuses (no real workspace can be created there).
   - In `REGULAR` mode, every demo function refuses.
   - At startup, `app.demo.enabled=true` with a `REGULAR` database stops the application (a new check in `StartupGuards`).

A mistake in one switch leaves the other closed. Both are enforced in the database, not only in Java.

### The entry: one click, no shared password (D2)

- `GET /demo` (public, only when enabled) shows one sentence and one button: "This is a demo with made-up shops, customers and payments.
  Nothing here is real." The button is a form with a CSRF token that posts to `POST /demo`.
- When the demo is enabled, an anonymous `GET /` redirects to `/demo`, and the login page shows a "Try the demo" link. Judges who open the
  app URL get the demo in one click.
- `POST /demo`:
  - rate-limited per IP (5 an hour) and globally (60 an hour);
  - calls `demo_claim()`, a database function that hands out one ready demo workspace (D3) and returns its tenant id and seller id;
  - builds the principal from the database row, exactly as login does (`AppUserRepository.findById` under that tenant). It must be an
    enabled SELLER of a `DEMO` tenant, or the request is refused;
  - renews the session id and the CSRF token, writes `DEMO_ENTERED` to the audit log, and redirects to `/app`.
- **The request carries no tenant, workspace or user value.** The server never reads one from the visitor.
- **No demo credentials exist.** The demo seller's password is random, 32 bytes, generated by the operator command, hashed with Argon2id
  and discarded. Nobody knows it, so it cannot be typed into the login form, here or on any other deployment.
- **Password login refuses demo tenants.** V020 replaces `resolve_tenant_for_login` so a `DEMO` tenant resolves to NULL. The two doors are
  disjoint: the login form reaches only regular tenants, and the demo button only demo tenants.
- **A real tenant can never become a demo tenant.**
  - `tenant.kind` is `REGULAR` or `DEMO`.
  - A CHECK ties `kind = 'DEMO'` to slugs `demo-…`, and `provision_tenant` refuses a `demo-` slug.
  - A trigger refuses any change of `kind` or `slug`, for every role that cannot alter triggers.

### One workspace per visitor: a pool, leased (D3, D4)

- The operator job keeps a **pool of ready demo workspaces**: 30 by default, 10 to 50. Each one is already seeded.
- Each click leases one to that visitor for **30 minutes**. Then the job retires it, the user status re-check (60 s) ends the session, and
  the visitor lands back on `/demo` with "Your demo has ended. Start a new one."
- Visitors never see each other's input, so a flooder or a hostile paste ruins only their own board. Every judge starts from the same
  seeded story.
- If the pool is empty, `/demo` says "All demo workspaces are in use. Try again in a few minutes." It never falls back to a shared or a
  regular tenant.
- The application role gets EXECUTE on `demo_claim()` only. It still cannot create a tenant or a user.

### Limits and abuse

| Abuse | Bound |
|---|---|
| Claiming many workspaces | 5 claims an hour per IP, 60 an hour in total (in-memory, like the login limits). The pool size caps live workspaces |
| Flooding one workspace | The existing ingestion limit (60/1m, burst 30, per tenant) and action limit (120/1m per user). New: a scenario limit of 20 per 10 minutes per tenant. A **row cap** in the database: at most 300 orders plus payments per demo tenant, refused by a trigger, answered with "This demo workspace is full. Start a new demo." |
| Hostile text (scripts, bidi, formulas) | The defenses already in place: escaped output only, `Display` cleaning, `PhoneMask`, CSV neutralisation, the strict CSP. The simulator routes accept **no text** at all, only a scenario name and a step from a fixed list (D14) |
| A real phone number pasted into the demo (requirement 7.3) | **Demo tenants refuse any phone number outside the synthetic range `+999`** (D11), in orders and payments. The input is rejected with a sentence |
| Real names or numbers in free text | They cannot be detected. Retirement scrubs the raw text and the names (section 4). Until then only the visitor who typed them can see them |
| The demo seller's power | Full SELLER permissions (D16): confirm, undo, resolve, export. Everything stays inside the visitor's own synthetic workspace |
| Claiming without JavaScript or cookies | Same behaviour: a plain form. No cookie means no session, so the claim is refused by CSRF (403) |

### What the scheduled reset removes

Nothing is deleted (section 4). Retiring a workspace:

- disables it, which ends its sessions within 60 s;
- overwrites the personal-data columns: raw message text, customer and sender names, phone hashes and last four digits, delivery notes;
- leaves the ledger, the audit log and the flags as they are, since they hold no personal data (ids, amounts, fixed sentences).

A full wipe of the demo instance is the operator dropping and recreating its separate database (task 13 runbook). That needs no trigger
change.

## 2. The chat simulator and the confirmation feed

- **A new pure module, `demo`** (no web dependency, like `suspicion`). It generates order messages and confirmation texts from:
  - a fixed list of obviously fictional names (D20), for example "Ana Testa", "Bea Sample", "Cy Example", "Dee Demo", "Eve Mock";
  - synthetic phones `+999 000 0NN NNNN`;
  - the demo catalog (section 4);
  - a clock.
- **The order text** uses the two shipped formats (labelled lines and one line), in English and in French. Items are named without a price,
  so the total comes from the catalog. For example:
  - `Order: D-7K2Q` / `Name: Ana Testa` / `Phone: +999 000 001 1001` / `2 x rice 5kg` / `1 x palm oil 1l`
  - `Commande: D-9M4X` / `Nom: Bea Sample` / `Tél: +999 000 001 1002` / `2 x riz 5kg`
- **The confirmation text** is built from the loaded template specs (`PaymentTemplates`): their literal text, zone and date format. It is
  never a second hard-coded copy, so a template change cannot silently break the demo.
  - **Every timestamp is "now minus an offset"**, formatted in the template's own zone (`+01:00`) and format (`dd/MM/yyyy HH:mm`).
  - Offsets lie in [0, 4 days]: 5 minutes for ordinary payments, 3 hours for R5, 4 days for "late payment" (R9). They are never ahead of
    now and never near the 35-day edge.
  - Minute precision truncates down, so a formatted time is never after now.
- **Both go through the same ingestion services as real input.** The routes call `OrderIngestionService.ingest` and
  `PaymentIngestionService.ingest` with:
  - the visitor's own user id as actor (the demo seller, so trusted);
  - external ids `sim:<uuid>`.
  
  There is no other write path: no SQL insert into orders or payments, not even in the seed (section 4). Every limit, check, flag and
  matcher step applies as for a pasted message.
- **Pages** (D15), phone-first, server-rendered, no new script, no inline style (the template lint applies):
  - `GET /app/demo` lists the seven scenarios. Each card shows its chat as bubbles (customer on the left, shop on the right) and its
    confirmation as an SMS-style feed item, with two buttons, "Send the order" and "Send the payment", plus "Run both".
  - A label says "Simulated chat". There is no WhatsApp name, logo or colours (their trade dress is not ours to copy).
  - `POST /app/demo/scenarios/{scenario}/{step}` runs one step; `step` is `order`, `payment` or `both`. With htmx it answers in place (the
    notice and the chips); without JavaScript it redirects to the board with the notice.
  - The bubble text is shown for reading only. The buttons send it. I considered a "copy to the paste box" link and dropped it, because it
    would put message text in a URL.
- **Who may use them.**
  - `RECORD_ORDER` and `RECORD_PAYMENT`.
  - **The tenant must be a demo tenant**, asked of the database (`tenant.kind`) on every request. Any other tenant gets 404.
  - Available only when `app.demo.enabled=true`.
  - CSRF on every POST, and the action and scenario limits.
- **Each click gets fresh references** (`D-` and 4 random characters from the transaction-id alphabet), so a reference is never shared by
  two open orders. A shared reference would score `REFERENCE_AMBIGUOUS`.

## 3. The seven one-click scenarios, and the seed

### Scenarios (requirement 7.2), each with its expected outcome as a test

Each scenario writes a new order and payment through the services. The outcome is asserted on the database (status, match state, flag rows,
ledger entries) and on the board.

| # | Scenario (7.2) | What it sends | Expected outcome (the test) | What the visitor sees |
|---|---|---|---|---|
| 1 | Happy path | Order (catalog total 5,000) and a confirmation with `Ref` = its code, same phone and name, paid 5 min ago, `provider-a-received-ref` | `AUTO` match, order `PAID`, ledger `PAYMENT_MATCHED`, no flag | The card in Matched, "Matched automatically" with its reasons |
| 2 | Underpayment | Order 5,000, payment 3,000 with its reference and phone | `SUGGESTED` (score 70; not exact, so not automatic); after Confirm: `PARTIALLY_PAID`, 3,000 applied | Review, Confirm and Reject; then "Part paid: 3,000 XAF of 5,000 XAF" |
| 3 | Overpayment | Order 5,000, payment 5,600 with its reference | `OVERPAYMENT` (R11) flag on the pair, `SUGGESTED`; after Confirm: 5,000 applied, 600 to customer credit | Flagged with the sentence; "I checked this" |
| 4 | Duplicate confirmation | Scenario 1's pair, then the same confirmation again (another external id) | Second payment `DUPLICATE`, `DUPLICATE_TX` (R1), nothing posted for it | Flagged "This transaction id was already recorded…" |
| 5 | Screenshot-only payment | `provider-a-forwarded` (no transaction id, last four only) | `UNVERIFIED`, `NO_PROVIDER_ID` (R2), suggestion capped at 84, never automatic | Flagged; the explainer names the missing transaction id |
| 6 | Wrong sender name | Payment with the reference, from another synthetic phone, under a name unlike the customer's (French template `provider-b-recu-ref`) | `NAME_MISMATCH` (R8) and `PHONE_MISMATCH` (R7) on the pair, `SUGGESTED` | Both sentences on the suggestion |
| 7 | Late payment | A confirmation paid 4 days ago, for an order placed now | `PAID_OUTSIDE_USUAL_WINDOW` (R9) on the payment and `PAID_BEFORE_ORDER` (R5) on the pair, `SUGGESTED` | "The payment time is far from when it was recorded", the story of an old confirmation reused |

"Late payment" has no single meaning. I read it as a stale confirmation forwarded days later, which is the scam this product exists for. An
order cannot be back-dated (`created_at` is the database clock), so R5 fires too. Both sentences are true (D10).

### The seed (tasks.md): every flow and the required flags on a fresh workspace

Seeded by the operator job (section 4) through the same services, as the demo seller. About 11 orders and 10 payments, inside the burst of
30. Timestamps are relative to the seed time.

| Seed item | Expected outcome (asserted by `DemoSeedIT`) |
|---|---|
| Auto | `AUTO`, order `PAID` |
| Suggested | Exact amount, agreeing name, no reference: `SUGGESTED` (no `REFERENCE_UNIQUE_MATCH`) |
| Partial | 3,000 of 5,000, confirmed by the seed through `MatchingService.confirm` (a real seller decision): `PARTIALLY_PAID` |
| Over-payment | 5,600 for 5,000: R11 flag, `SUGGESTED` |
| Duplicate | The auto item's confirmation again: `DUPLICATE`, R1 |
| Unverified | Forwarded shape: `UNVERIFIED`, R2 |
| Unparsed | A chat line "Hello, are you open tomorrow?": order `UNPARSED`, in the Unparsed column |
| R5 | Payment paid 3 hours before its order (both seeded): `PAID_BEFORE_ORDER` on the pair |
| R8 | Payment from another phone under another name: `NAME_MISMATCH` |
| R10 | Two orders from one phone under two names: `SENDER_ALIAS` on the second order |
| Catalog totals | Every seeded order without prices has the total computed from the demo catalog (`PENDING_PAYMENT`, never `NEEDS_REVIEW` for a missing price) |

## 4. Seed, reset and the immutable ledger

### Who runs it

**Not the web application.** Two operator one-shot commands, launched like `provision-*` (`java -jar web.jar demo-init`,
`demo-refresh`), D6:

- **`demo-init`.** Once, on an empty demo database: sets `deployment_mode` to `DEMO`. It refuses if a regular tenant exists.
- **`demo-refresh`.** Every 10 minutes, from an external scheduler (the host's cron or scheduled job; task 13).
  1. **Retire** workspaces whose lease has ended (30 minutes by default), and ready ones older than 3 hours, so the seeded "today" stays today.
  2. **Top up** the pool to its size. For each new workspace:
     1. `demo_create_tenant(password_hash)` creates the tenant (`kind = DEMO`, slug `demo-<random>`, zone `Africa/Douala`) and its seller.
     2. `demo_seed_catalog(tenant, items)` writes the catalog.
     3. The seed runs through the ingestion services on the application login.
     4. `demo_mark_ready(tenant)` makes it available.

**Credentials.** The command starts a Spring context without a web server (profile `demo-ops`), so it uses the real services, `PhoneKeys`
and the matcher.

- **`DB_*` (the application login):** used for the seed. It is the same role and the same RLS as a visitor.
- **`DEMO_DB_*`:** a new login `chatledger_demo_operator` with EXECUTE on the demo functions only (D7) and no table privileges.
- **`APP_SECRET`:** must be the web instance's, so phone hashes agree.

It never uses the owner or migrate login. It refuses `DEMO_DB_*` placeholders, prints no secret, and maps SQL failures to SQLSTATE classes,
like `ProvisioningCommand`.

### The catalog

- Eight synthetic items, each as an English and a French row (the lookup is by exact key; there are no aliases): rice 5kg / riz 5kg,
  tomato box / boîte de tomates, palm oil 1l / huile de palme 1l, plantain bunch / régime de plantain, sugar 1kg / sucre 1kg, soap bar /
  savon, flour 2kg / farine 2kg, beans 1kg / haricots 1kg.
- Prices are fixed in code.
- Written by `demo_seed_catalog`. It refuses a non-demo tenant, more than 50 items, a tenant that is not being seeded, and bad names or
  prices (the table's own CHECKs).

### Idempotence

- Each workspace is seeded once, in state `SEEDING`.
- Seed external ids are `seed:<tenant>:<item>`, so a re-run after a crash replays the same messages (idempotent by tenant and external id)
  and adds nothing.
- `demo_mark_ready` refuses a workspace whose seed is incomplete (the expected rows are counted).
- A workspace stuck in `SEEDING` for more than 10 minutes is retired and replaced, never claimed.
- `demo-refresh` may run twice at once: an advisory lock in `demo_refresh_lock()` lets one through.

### The guarantee that a reset never touches a non-demo tenant

Each layer holds by itself:

1. **The database refuses it.** Every demo function begins by checking `deployment_mode = 'DEMO'` and the tenant's `kind = 'DEMO'`, inside
   its own transaction. `kind` cannot change (trigger). So a regular tenant cannot pass, even with a forged id.
2. **There is no regular tenant to touch.** The demo deployment's database has none: `demo-init` refuses when one exists, and
   `provision_tenant` refuses in `DEMO` mode.
3. **The role cannot reach anything else.** The demo operator role has no table privileges and no other function.

The tests aim at each layer alone (section 6).

### The ledger stays immutable

- No trigger is disabled, dropped or weakened. **A reset never deletes a row.**
- **Retire** (`demo_retire`):
  - sets `tenant.disabled_at`, and the demo row's state to `RETIRED`;
  - overwrites the personal-data columns of that tenant's `orders`, `payment` and `customer` rows. Raw text becomes a fixed sentence
    ("Removed when the demo ended."); names, phone hashes, last four digits and delivery notes become NULL or the same sentence, as each
    column's constraints allow. I have not yet checked each column's NOT NULL and CHECK constraints; that is a stop 2 item and is
    unverified here;
  - leaves `ledger_*`, `audit_log` and `flag` untouched. They hold ids, amounts, fixed codes and sentences only (requirement 9.6's own
    statement about the ledger).
- **Fresh workspace per lease.** A visitor never sees a reset in place. The next claim gets a new tenant.
- **Growth is bounded.** At most 60 claims an hour and 300 records per workspace: worst case about 1,440 workspaces and 430,000 records a
  day under deliberate abuse; realistic use is far below. The operator drops the demo database when needed. No purge of retired workspaces
  is built (it would need the triggers off).

### New schema (V020)

- `deployment_mode`: one row, `mode IN ('REGULAR','DEMO')`, default `REGULAR`. RLS is on with no policy (the application cannot read it).
  It is read through `demo_mode()`. **A table without a tenant policy:** added to the coverage test's allowlist, by name.
- `tenant.kind` (`REGULAR` default, `DEMO`), the slug CHECK and a trigger making `kind` and `slug` immutable.
- `demo_tenant`: `tenant_id` PK and FK, state `SEEDING`, `READY`, `CLAIMED` or `RETIRED`, and the time of each. RLS with a tenant policy; no
  grant to the application.
- The row-cap trigger on `orders` and `payment` inserts: for `DEMO` tenants only, at most 300 together. Its first step is one primary-key
  lookup, so a regular tenant pays one index probe per insert. That is named in the register.
- Replaced functions:
  - `resolve_tenant_for_login`: `DEMO` returns NULL;
  - `provision_tenant`: refuses in `DEMO` mode and refuses `demo-` slugs.
- New `SECURITY DEFINER` functions, each listed in the guard:
  - for the demo operator: `demo_create_tenant`, `demo_seed_catalog`, `demo_mark_ready`, `demo_retire`, `demo_due_for_retirement`,
    `demo_refresh_lock`, `demo_init`;
  - for the application: `demo_claim` and `demo_mode`.
- New role `chatledger_demo_operator` (NOLOGIN group) and its login, in `docker/initdb` and `.env.example`.

## 5. The demo video story (under three minutes)

The scenarios run in this order, on a freshly claimed workspace, at phone width. About 165 seconds:

| Time | Step | On screen |
|---|---|---|
| 0:00 | Open the app URL, "Try the demo" | The one-sentence landing page, then the board with a "Demo, made-up data" chip |
| 0:15 | The seeded board | Summary strip; Matched, Needs your attention, Flagged and Unparsed with their counts; one sentence per flag |
| 0:35 | Scenario 1, happy path: "Send the order", then "Send the payment" | The chat bubble, the SMS, the card moving to Matched by itself, with its reasons |
| 0:55 | Scenario 2, underpayment | A suggestion; Confirm; "Part paid: 3,000 XAF of 5,000 XAF" |
| 1:10 | Scenario 3, overpayment | Flagged: "The payment is more than the order…"; Confirm; the 600 in customer credit |
| 1:30 | Scenario 4, duplicate | Flagged: "This transaction id was already recorded…"; "I checked this"; it moves to the payments set aside |
| 1:45 | Scenario 5, screenshot only | Unverified, never automatic, with the reason |
| 2:00 | Scenario 6, wrong sender name | Two sentences on the suggestion; Reject |
| 2:15 | Scenario 7, late payment | The old-confirmation sentences |
| 2:30 | The books check, the CSV download | "The books balance", a spreadsheet-safe file |
| 2:45 | End | The board, "what needs my attention now" answered |

A browser-check walk follows this order exactly and records a screenshot per step. The video itself is task 14.

## 6. Weakened defenses, scope, decisions, tests

### Weakened defenses and their compensation

1. **A public endpoint signs a visitor in without a password** (`POST /demo`).
   - Off by default, and only on a database in `DEMO` mode.
   - It reaches only `DEMO` tenants, through one database function.
   - CSRF, a per-IP and a global limit, session fixation protection, an audit row, a 30-minute lease.
2. **The application role may call `demo_claim()`, which changes a row it cannot otherwise write.**
   - It changes only `demo_tenant.state` and `claimed_at`, only for a `READY` demo workspace, and only in `DEMO` mode.
   - The application still cannot create a tenant or a user.
3. **Nine new `SECURITY DEFINER` functions.** Each has a fixed `search_path`, is listed in the guard and is granted to one role.
4. **One table without a tenant policy** (`deployment_mode`). It holds no tenant data and cannot be read by the application. It is named on
   the coverage allowlist.
5. **The per-IP limit is weak behind a proxy** until task 13 trusts the forwarded header: every visitor then shares one key. The global
   claim limit and the pool size still bound it.
6. **Visitors act as sellers.** Each acts only in its own leased, synthetic workspace. The row cap, the ingestion and action limits apply.
7. **Free text may carry real personal data** that no rule can detect.
   - The phone rule refuses real numbers in parsed fields.
   - Only the visitor who typed the text sees it, for at most the lease plus 10 minutes, then it is scrubbed.
8. **Retired workspaces stay in the database** (no delete, by design of the ledger). They are disabled and scrubbed, growth is bounded, and
   the operator can drop the demo database.
9. **The ingestion services learn about demo tenants** (the synthetic-phone rule and the row-cap answer). It is one branch on
   `tenant.kind`, read with the tenant's money settings, and tested on both kinds.
10. **The reset depends on an external scheduler.** If it stops, the pool drains and `/demo` says "all in use". It never falls back to
    anything less safe. Task 13 must provide the scheduler (unverified for the chosen free host).
11. **The in-memory limiter has a single instance** (register item 5). The demo runs one instance.

### What I will not build in task 9

- Self-registration.
- The application creating tenants.
- A shared demo tenant.
- An in-application scheduler.
- A purge of retired workspaces.
- Any real WhatsApp or mobile-money connection, any AI.
- The deployment, the scheduler on the host, and the forwarded-header trust (task 13).
- The video (task 14).
- French UI strings (task 12).
- A text field on any simulator route.

### Decisions I need from you

| # | Decision | Recommendation | Reason |
|---|---|---|---|
| D1 | Where the demo lives | A separate deployment and database, with `deployment_mode` in the database and `app.demo.enabled` in config, both off by default, enforced at startup and in every function | The demo door and real data can never coexist |
| D2 | How a visitor enters | One-click `POST /demo` with CSRF; no shared password; password login refuses demo tenants | No credentials to leak or reuse; two disjoint doors |
| D3 | One shared demo tenant, or one per visitor | **Approved as changed:** a pool of 30 pre-seeded workspaces (bounds 10 to 50), one leased per visitor | Isolation between visitors; each judge sees the same story; the application never creates a tenant |
| D4 | Lease length | **Approved as changed:** 30 minutes (bounds 10 minutes to 4 hours); then retire; sessions end within the 60-second re-check | Long enough for a judge, short enough to bound abuse |
| D5 | How a reset deals with the immutable ledger | Never delete: disable and scrub personal data on retire; fresh workspace per lease; a full wipe is dropping the demo database | Keeps every trigger intact |
| D6 | Who runs seed and reset | Operator commands `demo-init` and `demo-refresh` (external scheduler, every 10 minutes); no `@Scheduled` | Privileged work stays out of the web role |
| D7 | The operator's database rights | A new `chatledger_demo_operator` role with EXECUTE on the demo functions only | Narrower than owner or provisioner |
| D8 | How seed and scenarios write | Through the same ingestion services, as the demo seller; external ids `seed:` and `sim:` | No back door; phone hashes and recorder trust are right |
| D9 | Text generation | A pure `demo` module, building texts from the loaded template specs (zone, format), offsets in [0, 4 days] before now | Never `UNPARSED` by date; follows any template change |
| D10 | Scenarios versus seed | Scenarios are requirement 7.2's seven; the seed is tasks.md's list plus R1, R5, R8, R10, R11; "late payment" means R9 (and R5) | Settles the two lists; R5 cannot be avoided without back-dating |
| D11 | Real phone numbers in demo input | Demo tenants refuse any phone outside `+999` | Requirement 7.3 for parsed fields |
| D12 | Bounding one workspace | A database trigger caps orders plus payments at 300 per demo tenant | Holds for every path, paste included |
| D13 | New limits | Claim 5/1h per IP and 60/1h in total; scenarios 20/10m per tenant; existing limits unchanged | Bounds the only new public action |
| D14 | Simulator input | Routes take a scenario name and a step from a fixed list, never text | No new text input path |
| D15 | Look of the chat | Generic chat bubbles and an SMS-style feed in our CSS, labelled "Simulated chat"; no WhatsApp marks | Requirement 7.1 without copying another product's trade dress |
| D16 | The demo user's role | SELLER | The demo must show confirm, undo, resolve and export |
| D17 | Catalog | Eight items, English and French rows, fixed prices, through `demo_seed_catalog` | Orders compute their totals from it (tasks.md) |
| D18 | Demo tenant zone | `Africa/Douala` (UTC+1, the templates' offset) | The board's "today" agrees with the confirmations |
| D19 | Task 13's "demo credentials documented" | Becomes "demo URL documented" (there are no credentials); I edit that line | Keeps tasks.md true |
| D20 | Names | A fixed list of obviously fictional names; a test fails on any other name or a phone outside `+999 000` in the demo module | Product rule: no real names or numbers |
| D21 | First screen after entry | The board, with a "Demo, made-up data" chip and a link to the scenarios | "What needs my attention now" stays the first answer |

### Test plan

**Unit, Docker stopped:**
- `demo` module:
  - each generated order parses with the real order pipeline and the demo catalog, with the expected total;
  - each generated confirmation, for each of the five templates, parses with the real payment pipeline at the real clock: `RECEIVED` or
    `UNVERIFIED`, never `UNPARSED`;
  - a jqwik property over clocks (midnight in `+01:00`, month and year ends), offsets in [0, 4 days], names and amounts: every text is
    accepted and its parsed time equals the intended minute;
  - names and phones are on the allowlist;
  - no template text is copied by hand (the generator reads the specs);
  - references are unique across 10,000 draws.
- `DemoProperties` bounds.
- The startup guard (demo enabled with a `REGULAR` mode refuses to start).
- Handler coverage with the two new public handlers.
- The template lint on the new pages.
- The command's argument and environment refusals.

**Integration (Testcontainers):**
- `DemoSchemaIT`, each layer alone:
  - every demo function refuses a regular tenant (with a forged id), and refuses in `REGULAR` mode;
  - `kind` and `slug` cannot change, for the owner too;
  - `provision_tenant` refuses in `DEMO` mode and refuses `demo-` slugs;
  - `resolve_tenant_for_login` returns NULL for a demo tenant;
  - the row cap;
  - the application role can call only `demo_claim` and `demo_mode`;
  - the operator role has no table privilege;
  - retire scrubs exactly the listed columns and leaves the ledger, the audit log and the flags byte-identical (fingerprint);
  - `demo_claim` under 16 threads hands out each workspace once.
- `DemoSeedIT`: each seed row of section 3 has its outcome and flag; orders have catalog totals; every payment was accepted; a second run
  adds nothing; trial balance zero.
- `DemoScenarioIT`: each of the seven scenarios has its outcome as in the table; the ingestion services are called (no other writer); a
  non-demo tenant gets 404 on every demo route.
- `DemoEntryIT`:
  - disabled: every demo path 404, `/` still goes to `/login`;
  - enabled: claim, principal from the row, session id renewed, `DEMO_ENTERED` audit row;
  - CSRF missing or wrong gives 403;
  - per-IP and global limits give 429;
  - an empty pool gives the sentence;
  - a retired workspace ends the session within the re-check interval (injected clock);
  - the login form cannot enter a demo workspace;
  - a real phone is refused in a demo tenant and accepted in a regular one.
- `DemoRoutesIT`: the board-style table for the new routes (anonymous, helper, other tenant, CSRF, limits).
- `DemoRefreshIT`: retire due leases and stale ready workspaces, top up to the pool size, a stuck seed is replaced, two concurrent refreshes
  do the work once.
- A browser walk of the video story at 390 by 844, with and without JavaScript.

**Mutation checks.** Every guard, as in tasks 7 and 8, on worker copies; kills must be failing tests; redundant guards deleted; equivalents
named. Planned groups:
- the two switches and the startup guard;
- each check at the top of each demo function;
- the `kind` and `slug` trigger;
- the provisioning and login replacements;
- the row cap;
- the synthetic-phone rule;
- the claim (state, mode, lock);
- the lease and retire selection;
- the scrub column list;
- the entry route (CSRF, limits, principal from the row, kind check);
- each scenario's inputs (amount, reference, phone, offset, template);
- the generator's window and zone;
- the route permissions and the demo-tenant check;
- the command's refusals.

### Unverified in this proposal

- The NOT NULL and CHECK constraints of every column the scrub touches (stop 2 reads each one).
- That `+999` is not an assigned country calling code (the repository already uses it as synthetic; I have not checked it against the ITU
  list here).
- That `Africa/Douala` has no daylight saving time (pinned in stop 2 by `DemoMaterialTest` against the JDK's tz data).
- Whether the chosen free host can run a scheduled command (task 13).
- The seed's run time and the per-insert cost of the row-cap trigger (not measured).

### Estimated credits

Not visible to me from inside the session (read it from the Kiro status bar). By scope I expect stop 2 to be close to task 8's stop 2: a new
module, a migration with nine functions, two commands, four pages and a browser walk. That is a guess, not a measurement.

## 7. The owner's additions (D22 to D25)

### D22: a second visit does not take a second workspace

`GET /demo` and `POST /demo` first look at the session. If it holds a signed-in user whose row is still enabled (re-read from the
database, as the status filter does), the answer is a redirect to `/app` and `demo_claim` is not called. Only an anonymous visitor, or one
whose workspace has been retired, takes a lease. Tested with the lease count before and after.

### D23: the client address behind a proxy

- `app.security.trusted-proxy-hops`: an integer from 0 to 2, default 0. Startup refuses any other value. The startup log names the value and
  says whether the forwarded header is read.
- At 0, `X-Forwarded-For` is ignored and the address is the socket address (tested: a spoofed header does not change the key).
- At N > 0, the client is the entry N hops from the right of `X-Forwarded-For` (all such headers joined in order). Each proxy appends the
  address it received from, so the last N entries were written by trusted proxies and the Nth from the right is the client as the first
  trusted proxy saw it.
- A header with fewer than N entries, or an entry that is not an IP literal, falls back to the socket address. It is never trusted
  blindly.
- The same address is used for the sign-in limits, so they do not differ by path. At 0 that is unchanged behaviour.
- Task 13 sets the value for the host (line added there).

### D24: the refresh runner

- `java -jar web.jar demo-refresh` runs once.
- `java -jar web.jar demo-refresh --every 600s` loops (60 s to 3600 s) until it is stopped.
- The container entrypoint starts the loop as a second process next to the web process. It holds only the demo operator login and the
  application login it seeds with, never inside the web JVM.
- Each pass takes a session-level advisory lock on its own connection (`demo_refresh_lock()`). A second runner finds it held and does
  nothing for that pass. The lock goes away when the connection closes.
- A failed pass is logged by class name and SQLSTATE only, and the loop goes on.
- If the chosen host cannot run a second process, the register and task 13 say so. The fallback is the host's scheduled job running the
  one-shot form.

### D25: size

Measured in stop 2 by `DemoSizeIT` (growth of the test database over ten workspaces of each kind, PostgreSQL 16.4 in the test
container, indexes included). Not measured on a hosted tier.

| What | Rows | Bytes |
|---|---|---|
| One workspace after seeding: tenant, user, demo row, 16 catalog rows, 10 orders and their lines, 8 payments, customers, matches, flags, accounts, postings, audit rows | 129 | about 100 KB |
| After a typical visit (the seven scenarios once, three decisions) | 236 | about 150 KB |
| A workspace at the row cap (300 orders and payments), unverified extrapolation | about 2,000 | about 1.5 MB |

- 30 days at the claim limits (60 claims an hour, each workspace visited typically) is 43,200 workspaces: about 6.5 GB (unverified
  extrapolation). Every workspace filled to the row cap would be about 65 GB.
- A realistic contest month (a few hundred claims) is under 0.1 GB.
- Small free PostgreSQL tiers are commonly 0.5 to 1 GB. I have not checked the chosen host's limit, so this is unverified.
- **It fits for realistic use and does not fit at the claim limits.** The operator watches `pg_database_size` (task 13 runbook). Before
  80% of the tier, the operator drops the demo database and runs `demo-init` and `demo-refresh` again. That needs no trigger change and
  touches no real workspace: there are none in a demo database.
