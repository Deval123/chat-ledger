# Design: chat-ledger

## Overview

Java 21, Spring Boot, PostgreSQL. Server-rendered UI with Thymeleaf and HTMX, so the
page stays light on slow phones. The ledger is its own Maven module with no web
dependency, so it can be reused later.

## Architecture

```mermaid
flowchart LR
  A[Chat message text] --> B[Ingestion API]
  S[Confirmation SMS text] --> B
  B --> C[Extraction pipeline]
  C -->|parsers, then optional AI| D[(Orders and Payments)]
  D --> E[Matching engine]
  E --> F[Ledger core]
  E --> G[Suspicion rules]
  F --> H[(Ledger entries)]
  D --> I[Board UI]
  G --> I
```

## Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `ledger-core` | Accounts, immutable entries, balance invariant, reversals | nothing |
| `ingestion` | Endpoints, idempotency, input validation, HMAC | `extraction` |
| `extraction` | Order parser, SMS templates, optional AI extractor, validator | nothing |
| `matching` | Scoring, thresholds, partial and over payments, overrides | `ledger-core` |
| `suspicion` | Rule set that produces flags with reasons | `matching` |
| `web` | Board, simulators, exports, auth | all |

## Data model (main tables)

- `tenant(id, name, currency, scale, slug UNIQUE, disabled_at)` (`slug` and `disabled_at` arrive with task 3)
- `app_user(id, tenant_id, email lower-case, password_hash, role, disabled_at)`, unique per (tenant, email)
- `customer(id, tenant_id, display_name, phone_hash, phone_last4)`
- `orders(id, tenant_id, external_id UNIQUE per tenant, customer_id, total_minor, currency, status, raw_text, created_at)`
- `order_line(id, tenant_id, order_id, label, qty, unit_minor)` (`tenant_id` added in task 3, see "Tenancy and authentication")
- `payment(id, tenant_id, provider, tx_id UNIQUE per tenant+provider, amount_minor, currency, sender_name, sender_last4, paid_at, status, raw_text)`
- `match(id, tenant_id, order_id, payment_id, amount_minor, score, reasons_json, state, decided_by, decided_at)` (`tenant_id` added in task 3)
- `flag(id, tenant_id, subject_type, subject_id, code, reason, created_at, resolved_at)`
- `account(id, tenant_id, code, name, type)`
- `ledger_txn(id, tenant_id, kind, ref_type, ref_id, reverses_txn_id UNIQUE, created_at)`; a reversal points at the transaction it undoes, in the same tenant (V009)
- `ledger_entry(id, txn_id, account_id, debit_minor, credit_minor)` with a check that one of the two is zero, and no UPDATE or DELETE allowed by the application role.
- `audit_log(id, tenant_id, actor, action, subject, at)`

Default accounts: Accounts receivable, Mobile money clearing, Sales revenue, Customer credit (overpayments).

## Order and payment states

- Order: `PENDING_PAYMENT`, `PARTIALLY_PAID`, `PAID`, `NEEDS_REVIEW`, `UNPARSED`, `CANCELLED`
- Payment: `RECEIVED`, `MATCHED`, `UNMATCHED`, `DUPLICATE`, `UNVERIFIED`, `UNPARSED`
- Match: `AUTO`, `SUGGESTED`, `CONFIRMED`, `REJECTED`, `REVERSED`

## Ledger postings

- Order accepted: debit Accounts receivable, credit Sales revenue.
- Payment matched: debit Mobile money clearing, credit Accounts receivable (up to the order balance).
- Excess: debit Mobile money clearing, credit Customer credit.
- Unmatch: a reversal transaction that mirrors the original, never an edit.

## Matching score (initial weights, tunable)

| Signal | Points |
|---|---|
| Amount equals order balance | 50 |
| Reference code in the confirmation equals the order code | 25 |
| Sender name similarity to customer (normalized, accent-insensitive) | 15 |
| Timestamp within the expected window after the order | 10 |

Thresholds: score >= 85 auto-match; 60 to 84 suggest; below 60 leave unmatched.
Every candidate keeps its list of reasons so the UI can say why.

## Extraction pipeline

1. Normalize text (whitespace, digits, accents, common separators).
2. Orders: try each parser in order, first valid result wins. Payments: scan **every** template over the whole message; if more than one
   matches and they do not give the same result, the message is `UNPARSED` (`AMBIGUOUS_TEMPLATE`). Template order never decides money.
3. If none match and an AI extractor is configured, call it with a timeout and a JSON schema.
4. Validate: amount appears in the source text, currency known, date plausible. Reject otherwise.
5. Never pass phone numbers or names to an external service unless the operator has enabled it.

The default deployment has no AI extractor. A free host cannot run a local model, so the
live demo relies on deterministic parsers. The AI extractor stays behind an interface
and can point at any compatible endpoint.

## Provider templates

Templates are data, not code, and they are **literal text with typed holes, not regular expressions**: for example
`You received {amount} {currency} from {senderName} ({senderPhone}). Txn {transactionId}. {paidAt}`.
A template file (JSON) carries: id, provider code, currency, scale, whether a transaction id is required, a fixed UTC offset, a numeric
date format, the template text, at least three examples (with the fields they must give) and at least five counter-examples (texts that
must not match). Holes: `amount`, `currency`, `senderName`, `senderPhone` (or `senderLast4`), `transactionId`, `paidAt`, `reference`.
The demo ships five **synthetic** templates for two formats ("ProviderA", "ProviderB") and a forwarded shape. Templates ship in the jar;
adding one needs a release (see register item 35). Do not copy real operator wording into the repository.

## API (JSON, signed)

- `POST /api/v1/messages/order` body: `externalId`, `text`
- `POST /api/v1/messages/payment` body: `externalId`, `text`
- `GET /api/v1/board`
- `POST /api/v1/matches/{id}/confirm`, `/reject`, `/reverse`
- `GET /api/v1/export/ledger.csv`

Headers: `X-Signature` (HMAC-SHA256 over the body), `X-Tenant`. Idempotency by `externalId`.

`X-Tenant` is request input, so it must not decide whose data is touched. It only names
which signing key to verify with. The effective tenant is the tenant that owns the key that
verified the signature; a mismatch is rejected (task 11). See "Tenancy and authentication".

## Suspicion rules

Each rule is a small class returning zero or one finding with a code; the reason stored is the code's fixed sentence
(`FlagCode.sentence()`), never message text. Rules are independent and individually testable. Task 7 (decisions D1 to D13 in
`task-7-design-stop.md`, approved with R4 dropped) built ten codes:

| Rule | Code | About | Where it runs |
|---|---|---|---|
| R1 | `DUPLICATE_TX` | a payment | in the payment insert transaction |
| R2 | `NO_PROVIDER_ID` | a payment | in the payment insert transaction |
| R3 | `AMOUNT_NEAR_MISS` | a pair | under the matcher's lock (`applyWithin`) and with each suggestion |
| R5 | `PAID_BEFORE_ORDER` | a pair | the same |
| R6 | `SENDER_BURST` | a payment | in the payment insert transaction, under an advisory lock per sender phone |
| R7 | `PHONE_MISMATCH` | a pair | under the matcher's lock and with each suggestion |
| R8 | `NAME_MISMATCH` | a pair | the same |
| R9 | `PAID_OUTSIDE_USUAL_WINDOW` | a payment | in the payment insert transaction |
| R10 | `SENDER_ALIAS` | an order, or a payment | in the order insert transaction; in the payment insert transaction |
| R11 | `OVERPAYMENT` | a pair | under the matcher's lock and with each suggestion |

- **Storage (V019).** Typed `payment_id` and `order_id` with foreign keys to the same tenant; a CHECK ties each code to its shape; one flag
  per rule and subject (`INSERT ... ON CONFLICT DO NOTHING`); write-once except for one resolution (who and when), for every role, and never
  deleted. Existing `DUPLICATE` and `UNVERIFIED` payments were back-filled.
- **Matching (D3, D12).** Any flag, open or resolved, keeps a pair from automatic matching: a payment flag without an order blocks every
  pair of that payment, an order flag every pair of that order, a pair flag only that pair. The scorer's floor `NOT_FLAGGED` sees the
  subject's own flags; the wall in `applyWithin` sees all three scopes under the lock and degrades to a suggestion, written in the same
  transaction as the finding. Flags never refuse a confirmation or a manual match (D8).
- **Board (D9).** The Flagged column comes from open flag rows only. A checked unverified payment goes to Review, a checked repeat to the
  payments set aside or repeated.
- **Resolve (D7).** "I checked this": one click, seller only, audited `FLAG_RESOLVED`, once, no undo.
- **Settings.** `app.suspicion.*` thresholds with bounds checked at startup; `app.suspicion.disabled` lists codes switched off.

### Trade-offs and weakened defenses (task 7): register items 96 to 110, below.

## Other security rules

- Phone numbers stored hashed with a per-tenant salt plus the last 4 digits for display.
- Secrets from environment variables only. A pre-commit secret scan.
- The demo runs on its own deployment and database; each visitor gets a fresh synthetic workspace for a short lease, which is then
  retired, never deleted (task 9, section "Demo deployment").

Everything about tenants, login, sessions, roles, request limits and headers is in the next
section. Status: **proposed, awaiting approval** (task 3, requirement 9).

## Tenancy and authentication

Security steering applies: security outranks speed, scope and elegance, and every defense
that is weakened is named in "Trade-offs and weakened defenses" with its compensation.

### Threat model

- An anonymous visitor guessing workspaces, emails and passwords.
- A signed-in user of tenant A (SELLER or HELPER) trying to reach tenant B, or to do what
  their role forbids.
- A bug in our own code: a forgotten `WHERE tenant_id = ?`, or a tenant id taken from the wrong place.
- A stolen database backup (password hashes).
- Out of scope for task 3 and named as not done: multi-factor authentication, password
  reset, single sign-on, "remember me", a hostile operator.

### What was checked while writing this

Checked against documentation: PostgreSQL says foreign-key and unique checks always bypass
row security and that table owners, superusers and `BYPASSRLS` roles bypass it too (owners
unless `FORCE ROW LEVEL SECURITY`); a table with RLS enabled and no policy denies everything;
`TRUNCATE` and `REFERENCES` are not subject to RLS. OWASP's Argon2id minimum is 19 MiB, 2 passes,
1 lane. Spring's `Argon2PasswordEncoder` needs BouncyCastle, which the Boot BOM does not manage
(latest release on Maven Central: 1.86). htmx ships with `allowEval`, `allowScriptTags` and
`includeIndicatorStyles` on, and documents `hx-headers` for CSRF tokens.

Not yet verified, to be proven by tests in phase 2 and not assumed: that the transaction-local
tenant setting is still readable when a deferred constraint trigger fires at commit; the exact
Spring Boot 4.1 and Spring Security 7.1 property and API names; Spring's own Argon2 default
parameters (so explicit ones are used); whether BouncyCastle 1.86 carries advisories.

### Defects in the current schema that this design must fix

These come from reading V001-V009 against row-level security. None can be fixed by editing an
old migration, so each is fixed by a new one.

1. **V009's tenant trigger fails open under RLS.** `ledger_assert_entry_tenant` steps aside when
   it cannot see the account or the transaction, expecting the foreign key to report it. Under
   RLS another tenant's account is invisible, so the trigger steps aside, and foreign-key checks
   bypass RLS, so the entry commits. The function must treat "cannot see it" as a violation.
2. **Foreign keys can cross tenants.** Because FK checks bypass RLS, `orders.customer_id`,
   `order_line.order_id`, `match.order_id`, `match.payment_id` and the `decided_by`/`actor`
   columns accept another tenant's id if the caller knows the UUID. Each becomes a composite
   foreign key that includes `tenant_id`, as V009 already did for reversals.
3. **`order_line`, `match` and `ledger_entry` have no tenant column.** And `match` can link an
   order of tenant A to a payment of tenant B today.

### Decision 1: login and tenancy

`app_user` is unique per (tenant, email), so a login must say which tenant.

| Option | What | Cost |
|---|---|---|
| A. Workspace code | Login form has workspace, email, password. `tenant.slug` is a short public code. | One more field. The slug is an identifier, not a secret, so identical responses are needed to stop it being probed. New unique column. |
| B. Globally unique email | Change the unique key to `email` alone. | One person cannot work for two sellers without two emails. "Email already used" at user creation reveals accounts across tenants. Reusing the address after someone leaves is awkward. Changes V002's key. |
| C. Email first, then pick | Look the email up across all tenants, then ask which one. | Needs a query across tenants (an RLS exception). Reveals which tenants an email belongs to. |

**Recommendation: A.** It is the only option that needs no cross-tenant query and leaks nothing by design.

Slug: 3 to 32 characters, `[a-z0-9-]`, starts and ends alphanumeric, a short reserved list
(`admin`, `api`, `login`, `logout`, `static`, `health`). Email: trimmed, lower-cased with
`Locale.ROOT`, at most 254 characters, stored lower-case with a `CHECK`.

```mermaid
sequenceDiagram
  participant B as Browser
  participant F as Login chain
  participant DB as PostgreSQL
  B->>F: POST /login (workspace, email, password, CSRF token)
  F->>F: body size limit, rate limit by IP and by account key
  F->>DB: resolve_tenant_for_login(slug)
  alt workspace unknown or disabled
    F->>DB: same user lookup, with a random tenant id
    F->>F: verify the password against a dummy hash
  else workspace known
    F->>DB: set tenant for the transaction, find user by email (RLS applies)
    F->>F: verify the password (Argon2id)
  end
  F-->>B: failure: the same redirect and page every time
  F-->>B: success: new session id; tenant and role come from the database row
```

Pre-login lookup, without leaking tenants or users:

- The only privileged step is `resolve_tenant_for_login(slug) -> uuid`, a `SECURITY DEFINER`
  function that reads only `tenant`, returns only the id (or NULL), has a fixed `search_path`,
  and is executable by the application role alone. The user lookup then runs as normal, under RLS,
  for that tenant. There is no endpoint that lists workspaces or checks an email.
- Unknown workspace, unknown email, wrong password and disabled account give one response:
  same status, same redirect, same page, no distinguishing text. On an unknown workspace the
  same database work and a dummy password verification still run, so response time does not
  separate the cases. A small timing difference of a few milliseconds is a residual risk.
- Throttling (429) keys on what was submitted (workspace plus email, hashed), whether or not the
  account exists, so it is not an existence oracle.
- The principal's tenant, user id and role come from the database row, never from the form.
- No account lockout. Lockout lets anyone lock a seller out and can reveal an account; throttling is used instead.

### Decision 2: tenant isolation in depth

| Option | What | Cost |
|---|---|---|
| A. Application scoping only | Every query filters by tenant. | One missed filter is a data leak, and nothing else catches it. |
| B. Application scoping plus RLS | As A, plus PostgreSQL policies driven by a per-transaction setting. | Policies on every tenant table, a tenant transaction wrapper, some query overhead, and the defects listed above must be fixed first. |
| C. Per-tenant roles or schemas | The database itself separates tenants. | A role or schema per seller: migrations, pooling and operations all become much harder. Out of proportion for this product. |

**Recommendation: B.**

Layer 1, application. The tenant id is taken only from the authenticated principal (`CurrentTenant`).
A controller never accepts a tenant id from a path, parameter, body or header. A test scans every
controller handler and request type for a parameter or field named like a tenant and fails the
build on a match. Repositories keep taking the tenant id explicitly (the ledger already does).

Layer 2, PostgreSQL row-level security.

- `app_current_tenant()` returns `NULLIF(current_setting('app.tenant_id', true), '')::uuid`.
  Unset gives NULL, every comparison is not-true, and every read, insert and update is refused.
  It fails closed.
- Every tenant table gets `ENABLE ROW LEVEL SECURITY` and one policy for the application role,
  `USING` and `WITH CHECK` both `tenant_id = app_current_tenant()`. `tenant` itself allows only
  `id = app_current_tenant()`, read-only.
- Tables without a tenant column: `order_line` and `match` get a denormalised `tenant_id` with a
  composite foreign key to their parent (both are mutable and hold no data yet, so they can be
  back-filled). `ledger_entry` is immutable, so it cannot be back-filled; its policy is
  `EXISTS (SELECT 1 FROM ledger_txn WHERE id = txn_id)`, and RLS on `ledger_txn` applies inside it.
- `ENABLE`, not `FORCE`. The owner (`chatledger_migrate`) bypasses RLS. Forcing it would also
  subject Flyway and the `SECURITY DEFINER` functions to policies. The compensation is that the
  application role is neither owner nor superuser nor `BYPASSRLS`, and it holds no `TRUNCATE`
  or `REFERENCES` (which RLS does not cover). A catalog test checks all of that.
- The tenant is set with `set_config('app.tenant_id', :id, true)`, local to the transaction.
  It is gone at commit or rollback, so a pooled connection never carries a tenant to the next
  borrower. One entry point (`TenantScope.run`) opens the transaction and sets it first. Code
  that skips it sees nothing instead of everything. A test checks that a one-connection pool
  shows no tenant after a tenant transaction.

How RLS interacts with what exists:

- **Deferred balance trigger.** It fires at commit, as the invoking role, so it sees only the
  current tenant's rows, which is exactly the transaction's own entries. It needs the setting to
  still be readable at commit. I expect so (deferred triggers fire before transaction-local
  settings are reset) but that is an implementation detail, so a test proves it. If it were ever
  absent the trigger would see zero entries and refuse the commit. It cannot accept an
  unbalanced ledger.
- **Foreign keys.** They bypass RLS (checked). So tenant safety for references comes from
  composite foreign keys, not from policies. A catalog test fails if any foreign key between
  tenant tables does not include `tenant_id`.
- **V009's fail-open trigger.** A new migration replaces the function so that an invisible
  account or transaction raises an error. A test inserts an entry pointing at another tenant's
  account under RLS and expects a refusal.
- **Unique constraints.** They also bypass RLS, which could reveal that a value exists elsewhere.
  Every unique key except primary keys is already scoped by `tenant_id`. `tenant.slug` is the
  one global key, and it is public by design.
- **Immutability triggers, TRUNCATE.** Unaffected. They do not read rows.

How a test proves every tenant table is covered, including tables added later:

1. **Catalog test.** Every table in `public`, except an explicit allowlist (`flyway_schema_history`),
   has RLS enabled and at least one policy for the application role that calls `app_current_tenant()`.
   No policy is `USING (true)`. The application role owns no table, is not a superuser, has no
   `BYPASSRLS`, and holds no `TRUNCATE`, `REFERENCES` or `TRIGGER`.
2. **Registry test.** The set of tables with a `tenant_id` column, plus `tenant` and `ledger_entry`,
   must equal the keys of the isolation test registry. A new tenant table with no registered
   seeder and check fails the build with the table's name.
3. **Behaviour test per registered table.** Seed tenants A and B as the owner. As the application
   role: scope A sees only A's rows; scope B only B's; no scope sees none; inserting a row for the
   other tenant is refused; changing `tenant_id` is refused; updating or deleting the other
   tenant's row affects zero rows.
4. **Foreign-key test** from above, and a **repository contract test**: every `*Repository` class
   in `web` must be listed in a registry with a cross-tenant read test, or the build fails.
5. **Definer allowlist.** Any `SECURITY DEFINER` function not on an explicit list fails the build.

Limit, named: the setting is an ordinary session variable, so any SQL the application role runs
can set it. RLS therefore protects against our own bugs, not against SQL injection or arbitrary
SQL from the application role. Compensation: only parameterised statements, a test that fails on
string-built SQL in `web`, and least-privilege grants.

### Decision 3: password hashing

| Option | What | Cost |
|---|---|---|
| A. Argon2id | Memory-hard; OWASP's first choice. | Needs BouncyCastle (`bcprov-jdk18on`), a large library to keep patched. About 19 MiB per concurrent verification, a denial-of-service lever. |
| B. bcrypt | Built into Spring. | OWASP calls it legacy. 72-byte input limit. Not memory-hard. |
| C. PBKDF2-HMAC-SHA256, 600k | Built in, FIPS-friendly. | Weakest against GPUs. |
| D. scrypt | Memory-hard. | Needs BouncyCastle too, with less support than Argon2id. |

**Recommendation: A.** Explicit parameters, not framework defaults: Argon2id, memory 19456 KiB,
2 passes, 1 lane, 16-byte salt, 32-byte hash (OWASP's minimum). Stored through
`DelegatingPasswordEncoder` as `{argon2id}$argon2id$v=19$m=19456,t=2,p=1$...`, so the algorithm
and cost are in the hash and can be raised later. `bcprov-jdk18on` is pinned to an exact version
(1.86 at the time of writing) and `mvn -Psecurity-check verify` is run after adding it.
Denial-of-service guard: at most four verifications run at once; the rest wait briefly, then get 429.

Password policy: 12 to 128 characters, no composition rules, rejected if it contains the email
local part or the workspace code or appears in a small bundled list of very common passwords.
No call to an online breached-password service, because that sends data off the server.
Rehashing on login and password change are not in task 3.

### Decision 4: roles

| Option | What | Cost |
|---|---|---|
| A. Role checks by URL prefix | `/admin/**` for sellers. | Easy to miss a route; role names spread through the code. |
| B. Permissions mapped from roles, enforced with method security | A fixed role-to-permission table; `@PreAuthorize` on handlers; default deny. | A little more code and a coverage test. |
| C. Database-driven RBAC | Roles and permissions in tables. | Migrations, UI and caching for two fixed roles. Over-engineered. |

**Recommendation: B**, plus `/admin/**` requiring SELLER as a second guard.

| Permission | SELLER | HELPER |
|---|---|---|
| VIEW_BOARD | yes | yes |
| RECORD_ORDER, RECORD_PAYMENT | yes | yes |
| CONFIRM_OR_OVERRIDE_MATCH (confirm, reject, manual match, unmatch) | yes | no |
| RESOLVE_FLAG | yes | no |
| VIEW_LEDGER, REVERSE_TRANSACTION | yes | no |
| VIEW_AUDIT_LOG | yes | no |
| EXPORT_DATA | yes | **no** |
| DELETE_TENANT_DATA | yes | **no** |
| MANAGE_USERS | yes | **no** |

Reason for the middle rows: "advice is advisory, the seller always has the last word". A helper
records sales and sees the board, and does not decide or touch the books.

Enforcement: authorities are the permissions; the filter chain ends in deny-all; a test walks
every controller handler and fails if one has neither `@PreAuthorize` nor a place on the public
allowlist (login page, health, static files, error page); an exhaustive role-by-permission test
compares the mapping with an independent expected table. A user cannot change their own role.

### Decision 5: tenant and first-user creation

No public registration route exists (a test asserts `/register` and `/signup` are not served).

| Option | What | Cost |
|---|---|---|
| A. Startup hook when env vars are set | Creates the tenant at boot. | Runs on every boot; a plaintext password sits in the long-lived process environment. |
| B. One-shot provisioning command | A separate run that creates one tenant and one SELLER, then exits. | A dedicated database role and a `SECURITY DEFINER` function. |
| C. Hand-written SQL | Operator inserts rows with a hash. | Bypasses the password policy and the slug and email rules; easy to get wrong. |

**Recommendation: B** (approved), with a dedicated login role `chatledger_provisioner` that has no
table privileges and `EXECUTE` on two functions: `provision_tenant(slug, name, email, password_hash)`
and `provision_user(slug, email, role, password_hash)`.
Reason for a third role: the running application must not be able to create tenants or users, and the
migration role is meant for Flyway only. Reusing either would weaken a stated defense.

There are two operator-run commands, each a separate one-shot run that exits, with no UI:
`provision-tenant` (a tenant and its first SELLER) and `provision-user` (a SELLER or HELPER in an
existing tenant). Neither is reachable over HTTP.

- **The password is never taken from the command line or from an environment variable.** It is read
  from a prompt (no echo, when a console is attached) or from standard input, hashed in the process,
  and only the hash reaches the database. Neither command accepts a password or a hash as an argument
  or an environment variable, so there is nothing to leak through shell history, `ps` or `docker inspect`.
  It is read twice on a prompt and must match; from standard input it is read once, up to the first newline.
- The other inputs (workspace code, name, email, role) are not secret and come from arguments. The
  provisioner's own database credentials come from environment variables (`PROVISION_DB_USER`,
  `PROVISION_DB_PASSWORD`), because they are infrastructure secrets, not user passwords.
- Fails fast on a missing value, a `<replace-...>` placeholder, a weak password or a bad slug, email or
  role. `provision-tenant` refuses, without changing anything, if the slug exists; `provision-user`
  refuses if the workspace is unknown or the email is already used in it. Never prints a secret, and
  gives the same error for an unknown workspace as for any other failure.
- No default password anywhere. Migrations contain no `INSERT` into `tenant` or `app_user`
  (a test scans them). Properties files take passwords only from `${ENV}`. `.env.example` holds
  placeholders only. Test credentials are generated randomly per run.

### Decision 6: rate and size limits

| Option | What | Cost |
|---|---|---|
| A. In-memory token buckets | A small limiter in the application, bounded cache. | One node only; resets on restart. |
| B. A rate-limit library (Bucket4j) | Same idea, off the shelf. | Another dependency to pin and scan for the same single-node result. |
| C. Database counters | Shared between nodes. | A database write on every request, including attacker traffic. |
| D. Reverse proxy or gateway | Limits before the app. | Not available on a free container host. |

**Recommendation: A**, hand-written (about a hundred lines, tested with an injected clock) on a
Caffeine cache (already managed by the Boot BOM) capped at 100,000 keys, entries expiring after an hour.
It sits behind a `RateLimiter` interface so a database or Redis version can replace it.

Defaults (all configurable):

| What | Key | Limit |
|---|---|---|
| `POST /login` | client IP | 10 per 10 minutes |
| `POST /login` | hash of workspace + email | 5 per 15 minutes; reset on success |
| `GET /login` (each creates an anonymous session) | client IP | 30 per 10 minutes |
| Password verifications at once | node | 4 |
| Message ingestion | tenant, from the principal | 60 per minute, burst 30 |

Over the limit: `429` with `Retry-After` and a generic body. Size limits, by a filter that checks
`Content-Length` and counts streamed bytes (so chunked bodies are caught) plus Tomcat settings:
login 4 KiB, ingestion JSON 16 KiB, everything else 64 KiB, headers 16 KiB, multipart off. A rejected
request gets `413` before it is read. Every field is validated for size, type, range and
encoding (invalid UTF-8, NUL and control characters are rejected, not repaired).

Limits, named: single node; a restart clears the counters; with N nodes the limits multiply by N;
a flood of more than 100,000 distinct keys can evict an entry (each such request is itself limited
by IP, so this needs many addresses). Client IP is the socket address unless trusted-proxy
forwarding is configured at deployment (task 13). Trusting `X-Forwarded-For` blindly would let
anyone spoof their way past the per-IP limit; not trusting it behind a proxy makes all users share one IP.

### Decision 7: session, CSRF and headers

Session store:

| Option | What | Cost |
|---|---|---|
| A. Server-side session in memory | Servlet container `HttpSession`. | A restart signs everyone out; one node only. |
| B. Spring Session JDBC | Sessions in PostgreSQL. | Another dependency and a non-tenant table the application writes; an RLS exception to register. |
| C. Signed cookie (JWT) | Stateless. | Hard to revoke; larger attack surface. Rejected. |

**Recommendation: A.**

- Cookie: `HttpOnly`, `Secure`, `SameSite=Strict` (approved), `Path=/`, no `Domain`, name prefixed
  `__Host-` in production. The cost accepted: a link into the app from another site arrives signed
  out until the next click. The development profile may drop `Secure` for plain-HTTP localhost, and
  startup refuses to run with the `prod` profile and `Secure` off.
- Timeouts (approved, and configurable): 30 minutes idle and 12 hours absolute, 10 minutes for an
  anonymous session. The absolute limit is a small filter, since the container only has idle. They are
  set by `app.security.session.idle-timeout`, `app.security.session.absolute-timeout` and
  `app.security.session.anonymous-timeout`, validated at startup (each must be positive, and the
  absolute timeout must be at least the idle timeout). No "remember me".
- Fixation: the session id changes at login (`changeSessionId`), and a test shows the old id no longer works.
- A user's status and role are re-read at most every 60 seconds per session, so disabling someone
  or changing a role takes effect quickly without a query on every request.
- Logout is `POST /logout` with a CSRF token, invalidates the session, deletes the cookie and sends
  `Clear-Site-Data`. `GET /logout` is not mapped.

CSRF:

| Option | What | Cost |
|---|---|---|
| A. Token in the session | Spring's default, masked per request. Thymeleaf adds a hidden field; htmx sends it in a header. | Needs a session for the login page too. |
| B. Double-submit cookie | Token in a readable cookie. | The cookie must be readable by script; weaker. |
| C. SameSite only | No token. | Not enough on its own. |

**Recommendation: A.** Every `POST`, `PUT`, `PATCH` and `DELETE` needs the token, including login
and logout; no state change happens on `GET`. Forms keep Thymeleaf's hidden `_csrf` field. For
htmx, the layout puts the token on `<body>` as an `hx-headers` attribute (an attribute, not a
script), which htmx documents. htmx's `hx-boost` does not refresh `<body>`, so forms keep the
hidden field as well. A missing or wrong token gives `403`. htmx is configured through a meta tag
with `allowEval`, `allowScriptTags` and `includeIndicatorStyles` all off (its defaults are on, and
the last one injects an inline style), and `selfRequestsOnly` on. The htmx library itself is added
in task 8, pinned and served from our own origin; task 3 delivers the layout and the contract.

Content Security Policy, enforced from day one, with no inline code anywhere:

```
default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self';
connect-src 'self'; form-action 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'
```

Rule behind it: templates contain no inline `<script>`, no `style=` attribute and no `on...=` handler,
and no `th:utext` or unescaped inline expressions; a test reads every template and fails otherwise.
All output goes through Thymeleaf's escaping.

Other headers: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
`X-Frame-Options: DENY` (old browsers; `frame-ancestors` is the real control),
`Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Resource-Policy: same-origin`, a
`Permissions-Policy` that turns features off, `Cache-Control: no-store` on authenticated pages
(versioned static files stay cacheable, which matters on slow connections). HSTS
(`max-age=31536000; includeSubDomains`, no `preload`) is sent only on HTTPS requests, and behind a
TLS-terminating proxy only once forwarded-protocol headers are trusted (task 13). No CORS. The error
pages show no message, stack trace or framework version.

### Logging and audit

No password, hash, session id, CSRF token or email appears in any log. Security events (login
success and failure, logout, access denied, CSRF rejected) log ids and a short keyed hash of the
submitted workspace and email, plus the client IP for failures. A test captures the logs of every
login, logout and CSRF path and searches them for the password, `argon2`, the session id and the token.
Successful logins, logouts and denials are also written to `audit_log` (insert and select only for
the application role). A failure for an unknown workspace has no tenant, so it goes to the
application log only.

### Schema changes

New migrations only; V001 to V009 are never edited. Each states its grants explicitly, uses no
default privileges, and leaves the application role owning nothing.

- **V010 tenancy integrity.** `tenant.slug` (unique, format check) and `disabled_at`; `app_user`
  lower-case email check and `disabled_at`; `UNIQUE (id, tenant_id)` on parents; `tenant_id` on
  `order_line` and `match`, back-filled; composite foreign keys replacing the single-column ones.
- **V011 row-level security.** `app_current_tenant()`, RLS and policies on every tenant table,
  the fail-closed replacement of `ledger_assert_entry_tenant`, and `audit_log` limited to insert
  and select.
- **V012 authentication plumbing.** `resolve_tenant_for_login`, `provision_tenant`, `provision_user`,
  their `EXECUTE` grants. The provisioner and application login roles are created by `docker/initdb`, not by a migration.

After task 3 the application role can read `tenant` (its own row) and `app_user`, insert and read
`audit_log`, and keeps its V008 ledger grants. It cannot create or alter tenants or users.

Effect on existing code and tests: `JdbcLedgerRepository` must run every call inside a tenant
transaction or it will see nothing; the raw-SQL helpers in `LedgerPostgresIT` and
`LedgerEntryImmutabilityIT` must set the tenant; `FlywayStartupIT` expects the new policies.
Seeding done as the owner is unaffected.

## Order extraction (task 4)

Full proposal and the owner's answers: `task-4-design-stop.md`. What was built, in short.

- **Entry point.** `OrderIngestionService.ingest(tenantId, actor, externalId, text)` in `web`. No HTTP endpoint yet: the paste form
  (task 8) and the signed machine API (task 11) will call it, so every defense below applies to both. The tenant is always a
  parameter taken from the principal (or the verified signing key), never from the message.
- **Steps.** Re-check the input (`InputChecks`, refuse and never repair) → per-tenant rate limit → idempotency lookup → read the
  tenant's currency and scale from its own row → `OrderPipeline` (normalise, parse, validate, catalog lookup, decide) → one
  transaction for customer, order, lines and audit row.
- **Parser.** Deterministic and strict, two shapes only (labelled lines, one line), English and French labels as constants
  (`Order/Commande`, `Name/Nom`, `Phone/Telephone`, `Total/Montant`, `Deliver/Livraison`). `FCFA`, `F CFA` and `XAF` are the tenant's
  XAF; any other currency word is kept as written and refused by the validator. No pattern is built from the message, no
  backtracking, no reflection or format strings (a source scan enforces it). Anything not understood fails and the message is kept as
  `UNPARSED`; it is never dropped and never guessed.
- **Normalisation** works on a derived copy: format characters (bidirectional overrides, zero-width) are removed, NFKC applied, digits of
  any script mapped to ASCII, spaces and line breaks reduced. The stored `raw_text` is the original, byte for byte.
- **Validator versus catalog.** Every amount the **parser read** (a price, a line total, the stated total) must be written in the
  message; this is checked on the normalised text, independently of the parser. A total **computed from the catalog** is not in the
  message: the decider computes it with `Math.*Exact`, caps it at 10^12 minor units, and does not require it to appear in the text.
  A stated total is never replaced by a computed one: if they disagree the order is `NEEDS_REVIEW` with the stated number kept.
- **Statuses and nullability.** `PENDING_PAYMENT` (total known), `NEEDS_REVIEW` (a price is unknown, or the totals disagree),
  `UNPARSED`. Only `total_minor` (and a line's `unit_minor`) may be NULL, and a CHECK ties a NULL total to `UNPARSED` / `NEEDS_REVIEW`
  only. `currency` is always the tenant's and never NULL.
- **Phones.** One function, `PhoneNumbers.canonicalDigits` (digits only, a leading `+` or `00` removed, 7 to 15 digits, no country
  logic), feeds both the HMAC and `last4`. The hash is HMAC-SHA-256 under a per-tenant key derived from `app.secret` (`PhoneKeys`),
  so the key is not in the database. Tasks 5 and 6 must use the same two classes to find a payer from an SMS. Named limit: the same
  number written with and without a country code is two customers.
- **Customers** are found or created by phone hash inside the order transaction (an order that is not stored leaves no customer);
  without a phone each named customer gets a row of their own.
- **Idempotency.** `UNIQUE (tenant_id, external_id)` with `INSERT ... ON CONFLICT DO NOTHING` is the arbiter. A replay returns the
  existing order; with different text it returns `DUPLICATE_DIFFERENT_TEXT` and changes nothing.
- **Database (V014).** `catalog_item` (RLS, application role SELECT only); nullable `orders.total_minor` and `order_line.unit_minor`;
  new `orders.order_ref` and `orders.delivery_note`; format and length CHECKs on every column that receives message text;
  `INSERT` (not UPDATE or DELETE) on `orders`, `order_line`, `customer` for the application role.
- **Logging.** `IngestionEvents` takes ids, enums and counts only. `ParsedOrder`, `Phone`, `OrderRepository.Stored` and the others
  print no text, name or number. `ExtractionResult.Failure` carries an enum (`FailureReason`), never text.

### Trade-offs and weakened defenses

| # | Defense weakened or not provided | Why | Compensation |
|---|---|---|---|
| 1 | The RLS tenant setting can be set by any SQL the application role runs | A plain session variable is the only practical per-transaction channel | Parameterised statements only, a test against string-built SQL, least privilege; application scoping stays the primary control |
| 2 | Owner and superuser bypass RLS (`ENABLE`, not `FORCE`) | `FORCE` would put Flyway and the definer functions under the policies | Application role is not owner, superuser or `BYPASSRLS`; migration role used by Flyway only; catalog test |
| 3 | Three `SECURITY DEFINER` functions (`resolve_tenant_for_login`, `provision_tenant`, `provision_user`) | Login lookup and provisioning need access the application role must not have | Fixed `search_path`, minimal return values, `EXECUTE` limited to named roles, an allowlist test |
| 4 | Workspace code is public | Needed to pick the tenant at login | Identical responses, equalised work, no listing endpoint |
| 5 | In-memory rate limiter | No shared store on the target host | Bounded cache, `RateLimiter` interface, limits named above, revisit at deployment |
| 6 | In-memory sessions, no concurrent-session cap | Single node | Idle and absolute timeouts, status re-check every 60 s, restart signs out (safe direction) |
| 7 | No MFA, no password reset, no remember-me | Out of scope for task 3 | Argon2id, throttling, password policy; reset is an operator action until a later task |
| 8 | No breached-password check | It would send data off the server | Length rule and common-password list |
| 9 | Argon2 memory per login is a denial-of-service lever | Memory-hardness is the point | Four at once, IP and account throttles |
| 10 | `Secure` cookie off in the dev profile | Plain-HTTP localhost | Startup refuses it under `prod` |
| 11 | CSP has no report endpoint; HSTS has no preload | Nothing to receive reports yet; preload is hard to undo | Revisit at deployment |
| 12 | Existing tests hard-code throwaway passwords for ephemeral containers, against the steering (paid down in task 3: all test and CI credentials are now random per run) | Written before this task | Generate random credentials per run in task 3 |
| 13 | Task 2.3 triggers can still be dropped by the owner | Inherent to PostgreSQL | Same compensation as 2 |

### Open conflicts in the spec

- **Requirement 9.6 versus 4.2: resolved.** "Delete its data" now means deleting the tenant's
  personal data, and the ledger stays (requirement 9.6 was rewritten). Personal data is: customers,
  customer names, hashed phone numbers, sender names and the raw text of messages and confirmations
  (`customer`, and the `raw_text`, `sender_name`, `sender_last4` and customer link on orders and payments).
  The ledger rows hold no personal data, only account ids, amounts, dates and opaque references, so
  deleting personal data never touches them and needs no relaxation of the append-only rules.
  Orders and payments that a ledger entry references are kept as anonymous records (amounts, status,
  dates); the personal columns are erased. The feature itself is built in a later task. Task 3 only
  guarantees HELPER can never do it.
- **Assumption to check: ledger retention.** Keeping the ledger after personal data is deleted is
  an assumption, not a verified legal position. It rests on two things that must be checked against
  local accounting and data-protection law before launch: that accounting records must or may be
  kept for a retention period regardless of a deletion request, and that account names and opaque
  references in the ledger are not themselves personal data. The `app_user` rows (the sellers' and
  helpers' own emails) are personal data too and are not covered by the rule above; what happens to
  them when a tenant leaves is a separate decision for the same feature.
- **`X-Tenant` in the API** is request input; resolved above (it only selects a signing key).
- Requirement 4.5 (CSV export) and 9.6 are SELLER-only by the matrix above.

## Payment extraction (task 5)

Full proposal and the owner's answers: `task-5-design-stop.md` (approved with D1 changed to option C and four additions). What was built.

- **Entry point.** `PaymentIngestionService.ingest(tenantId, actor, externalId, text)` in `web`. No HTTP endpoint yet (the paste form, task 8,
  and the signed machine API, task 11, will call it). Steps: re-check the input (`InputChecks`, 1,000 characters for a payment) → per-tenant
  rate limit (the same budget as orders) → idempotency lookup → tenant currency and scale from its own row → `PaymentPipeline`
  (normalise, scan templates, read typed fields, validate, decide) → duplicate lookup → one transaction for the payment and its audit row.
- **Templates are literal text with typed holes, matched by our own scanner.** No regular-expression engine and no new dependency. The scanner
  walks the normalised message once: a literal must be there exactly; a hole consumes characters its type allows, up to its maximum length,
  gives back trailing spaces to the next literal, and must reach its minimum; the whole message must be consumed. There is no search and no
  backtracking, so each character is looked at a constant number of times; the step count is exposed and tested. **Delimiter rule:** the first
  character of the literal after a hole must not be one the hole allows, so every hole has one unambiguous end. Caps: 500 characters per
  template, 10 holes, at least 12 literal characters, no two holes without a literal between them, each hole at most once, 8 KiB per file, 20 templates.
- **Hole character sets** are narrower than the database CHECKs. A transaction id or reference reads letters, digits, `_`, `-`; a currency reads ASCII
  letters (so `F CFA` is written as literal text in a template); an amount reads digits, space, `,`, `.`, `'`; a date reads digits and `/ - : . space`.
- **Gates (build and startup).** Each shipped file is bound strictly (no unknown field, no duplicate key, no wrong type, 8 KiB). Then `PaymentTemplate.compile`
  applies the structural rules above plus: fixed-width numeric date format (`yyyy MM dd HH mm`, optional `ss`, strict resolver), a fixed UTC offset,
  a transaction-id hole if and only if the template says it carries one, `amount` and `paidAt` required, not both `senderPhone` and `senderLast4`,
  and a template already written in normalised form. Then `PaymentTemplates.of` proves the set: every example must give exactly the stated fields,
  pass the validator, and be read the same by the whole set; no counter-example may match. **Any failure stops the application**
  (`IllegalStateException` naming the template id and a problem code, never text): a template silently skipped would turn a whole provider into `UNPARSED`.
- **Ambiguity.** Two templates may match one message only if they give the same result; otherwise `UNPARSED` / `AMBIGUOUS_TEMPLATE`. A provider text that sometimes has a
  reference is two templates (with and without it).
- **Fields.** Typed parsers: amount (`AmountParser`, scale from the tenant), currency (`FCFA`/`XAF` aliases, must equal the template's), transaction id, name,
  phone (`Phone`, `PhoneNumbers.canonicalDigits`), last four digits, reference (same format as `orders.order_ref`), date (strict, fixed offset). A captured
  value is never used raw. `PaymentValidator` (run once, in the pipeline) re-checks bounds, that the amount is written in the message, that the currency is the
  tenant's, and that the time is inside the window: at most 15 minutes ahead and 35 days back, else `DATE_IMPLAUSIBLE`.
- **Statuses.** `RECEIVED` (the template carries a transaction id), `UNVERIFIED` (a template that declares none; never auto-matched, task 6), `DUPLICATE`
  (same tenant, provider and transaction id as an existing non-duplicate payment; stored, linked by `duplicate_of`, never posted to the ledger, task 6),
  `UNPARSED` (nothing read; only the text and the tenant's currency are stored).
- **Sender.** A full number is hashed with `PhoneKeys` (the same function and key as customers) and its last four digits are kept; a `senderLast4` hole keeps only the
  digits. No customer is ever created from an SMS. `reference` is stored as written and compared with `ReferenceKeys.key` (strip, upper-case), the same key
  function as `orders.order_ref`.
- **Database (V015).** New `external_id` (unique per tenant), `reference`, `sender_phone_hash`, `duplicate_of` (composite foreign key with the tenant), `template_id`;
  `provider` and `amount_minor` nullable but tied to `UNPARSED` by a CHECK; the plain transaction-id UNIQUE becomes a partial unique index that leaves `DUPLICATE` rows out;
  format and length CHECKs on every text column (`NOT VALID`, existing table); `INSERT` (not UPDATE or DELETE) for the application role.
- **Logging.** `PaymentEvents` takes ids, enums and the template id only; `ParsedPayment`, `PaymentRepository.Stored` and `PaymentIngestionResult` print no text, name,
  number, transaction id or reference.

### Trade-offs and weakened defenses (task 5): register items 32 to 50, below.

## Matching (task 6), what stop 2a built

Full proposal and the owner's answers: `task-6-design-stop.md`. Stop 2b adds the service that applies a match; stop 3 the overrides.

- **Scoring is pure** (`matching` module): `MatchScorer` gives a score from 0 to 100 and reason **codes** from parsed fields only (no raw text is passed in, and a
  source test fails if matching code mentions it). Amount: exact 50, partial or over-payment (up to twice the balance) 20. Reference equal under `ReferenceKeys` 25, but
  0 if another open order has the same code. Identity up to 15: equal phone hash 15, otherwise name agreement 12 or 8, plus 3 for equal last four digits beside a name.
  Time 10. A payment without a transaction id is held below the automatic threshold.
- **An automatic match needs seven floors** (transaction id, exact amount, a unique matching reference, some identity, same currency, a trusted recorder, the kill switch
  on) plus the score and, in `MatchPlanner`, a lead of at least the tie margin over every other candidate. Floors are constants; thresholds are configuration with bounds
  (`app.matching.*`, `MatchConfig`), checked when the application starts.
- **`NameSimilarity`** compares a sender with a customer by tokens (accent-insensitive, apostrophes dropped, other punctuation separates), bounded at 8 tokens of 40
  characters from the first 100 characters, with `equals` and `startsWith` only.
- **V016.** `match` gets `excess_minor`, `ledger_txn_id`, `created_at`; every column is tied to the state by CHECKs; one active match per payment, one open match per pair, one
  posting per business event; transition triggers for order, payment and match status; deferred triggers that check at commit that the active matches of an order add up to at
  most its total, that an active match uses its payment whole, that the order status is the function of its active matches, that a payment is `MATCHED` exactly when it has one,
  that an automatic match has a transaction id, and that the posting has exactly the match's amounts on the right accounts. The application role gets `INSERT` on `match` and
  `UPDATE` of named columns only: `orders.status`, `payment.status`, and six `match` columns.
- **Ledger.** `JdbcLedgerRepository.appendWithin` posts on the caller's connection, so a posting can commit together with a match and a status change; the account and overflow
  checks are one shared class (`LedgerChecks`) used by both paths. `ChartOfAccounts` creates the four fixed accounts of a tenant idempotently inside the caller's transaction.

- **Stop 2b: `MatchingService.apply` is the only place an active match is created, in one database transaction** (`TenantScope`). It locks the payment row, then the order row (always
  in that order), re-reads both under the lock, re-scores an automatic match on the locked data (an automatic match that no longer qualifies is stored as a suggestion), applies
  `min(payment, balance)` and posts the rest as customer credit, ensures the four accounts and the order's `ORDER_ACCEPTED` posting, posts `PAYMENT_MATCHED` with `appendWithin`, writes
  the match, moves the order (`PAID` or `PARTIALLY_PAID`) and payment (`MATCHED`) statuses, retires the other open suggestions, and appends the audit row (`MATCH_AUTO` with no actor,
  or `MATCH_CONFIRMED` with the seller). Any failure rolls back all of it; the deferred triggers of V016 re-check the sums at commit. `StepHook` lets a test fail the transaction after
  each of the nine steps.
- **The engine.** `onPaymentRecorded` scores the open orders (at most `candidateCap`), plans, and either applies or stores suggestions (never for a pair the seller rejected).
  `onOrderRecorded` scores the open payments; if the plan is automatic it hands the decision to `onPaymentRecorded` for the best payment, so the choice is also made from the payment's
  side. Both ingestion services call a `MatchTrigger` after their own commit, best effort: a failure is logged by id and never changes what ingestion returns.
- **`ORDER_ACCEPTED` is posted in the order's ingestion transaction** (`OrderRepository.insertWithin`) when the order is `PENDING_PAYMENT`. If the posting fails, the caller gets an
  `IllegalStateException` ("Could not post an order to the ledger"), nothing is stored, and a retry with the same external id starts from nothing (one posting per order is a unique index).
- **Who recorded a payment (`Recorder`) is read from the append-only audit entry `PAYMENT_INGESTED`** (subject = the payment id) joined to `app_user.role`: only the
  exact role `SELLER` is trusted; anything else is a helper, **including a missing actor and any stored word `MACHINE`**. The earliest entry of the tenant wins. A helper's
  payment is never automatic: it is stored as a suggestion. `Recorder.MACHINE` exists for the signed machine feed (task 11), which will record an explicit machine identity in
  the audit entry; nothing stored today can produce it (register item 67).
- **V017** adds a partial index for that lookup. The matching switch `app.matching.auto-enabled` stays on by default.
- **Stop 3: the seller's decisions** (`MatchingService`): `confirm` (the named suggestion, still open, on the balance at that moment), `reject`, `matchManually`
  (any open order with any free payment, audited `MATCH_MANUAL`), `unmatch`, `markUnmatched` and `reopen` (the only way a payment becomes or stops being `UNMATCHED`), and
  `rematch`. Each is one transaction, begins by asking the database whether the user is an enabled seller of the tenant (`app_user`: role, `disabled_at`), and uses the one lock
  order: payment, order, match (a rejection locks the match alone). An **unmatch** posts the reversal of the match's posting with `JdbcLedgerRepository.reverseWithin` (a mirror
  transaction linked to the original, refused if it was already reversed), marks the match `REVERSED` with who and when, recomputes the order from the matches that remain
  and frees the payment (`RECEIVED`, or `UNVERIFIED` if it has no transaction id). **The seller has the last word:** a pair the seller rejected or reversed, and the pairs of a
  payment set aside, are never scored, proposed or applied by the engine again (the apply step re-checks under the lock); only a match by hand can bring them back.
  Property tests (`MatchingBooksPropertyIT`) check, after every step of random sequences, the books independently of the service (`MatchInvariants`).
### Trade-offs and weakened defenses (task 6, so far): register items 51 to 73, below.

## Board and summary (task 8)

The decisions D1 to D22 are in `task-8-design-stop.md` (approved). What was built:

- **Pages** (Thymeleaf, under `/app`, tenant and user from the session only): the board (summary strip, two paste forms, five columns with
  chips, footer), one column with cursor paging (20 a page, at most 5 pages), the order and payment pages with matches and their reasons, the
  check pages (review a total, undo a match, match by hand), the daily summary, the books check, the downloads. `BoardController` reads,
  `ActionController` changes (POST only), `ExportController` streams CSV. Every handler has `@PreAuthorize`; an id that is not the tenant's
  is a 404; a refusal of a service is a fixed sentence (`Notice`, `messages.properties`), never an error page.
- **Read model.** `BoardRepository` places every order and payment in exactly one column with one SQL `CASE` (D1: unread, then flagged or
  a repeat or unverified payment, then review, then paid, then awaiting; set-aside payments behind a link), counts the columns, reads the
  details (raw text only when asked and only for unread, review and flagged items), and the summary (D8). `ExportRepository` streams at most
  the cap. `BooksCheck` compares the ledger with the orders and matches six ways, on demand, cached five minutes per tenant.
- **htmx 2.0.11**, vendored (`web/third-party/htmx`), progressive only: the same handler answers a redirect with a notice code, or for
  `HX-Request` the changed card, the notice and the chips (out of band). Configuration: no eval, no script tags, no injected style, own
  origin only, no history snapshots, and 4xx/5xx answers swapped into `#notice`. `hx-headers` on `<body>` carries the CSRF token and
  `Accept: text/html`.
- **Approval of a `NEEDS_REVIEW` order** (`OrderApprovalService`, V018): review page, then a second look at the amount as it will be
  posted, then one transaction (seller, lock, status, bounds, total and status, `ORDER_ACCEPTED`, audit). In the database the total may
  change only in that move, and the move needs its posting by commit.
- **Limits:** rematch 3 a minute and 20 an hour per tenant (token buckets), export 6 per 10 minutes per tenant, 120 actions a minute per
  user, all validated at startup.
- **Text:** printed only with `th:text`, through `Display` (control, format, private-use, unassigned characters and lone surrogates
  removed, at most two combining marks, cut with an ellipsis); phones as the last four; raw text masked by `PhoneMask.maskText`; CSV text
  cells neutralised by `CsvWriter`.
- **Browser check** (`tools/browser-check`, puppeteer-core, outside the Maven build and CI): a scripted walk at 390 by 844 with and without
  JavaScript.

### Trade-offs and weakened defenses (task 8): register items 76 to 95, below.

## Demo deployment (task 9)

The decisions D1 to D25 are in `task-9-design-stop.md` (approved; D3 and D4 changed, D22 to D25 added by the owner). What was built:

- **Two switches, both off by default.** `app.demo.enabled` (config) and `deployment_mode` (one row in the database, REGULAR until the
  demo operator's `demo_init` moves it to DEMO on a database with no regular workspace, never back). The application refuses to start
  with the first on and the second off. In DEMO mode `provision_tenant` refuses; in REGULAR mode every demo function refuses.
- **Workspaces.** `tenant.kind` (REGULAR or DEMO) is tied to the `demo-` slug prefix and frozen with the slug by a trigger. `demo_tenant`
  holds the pool (SEEDING, READY, CLAIMED, RETIRED; forward only; never deleted); its key includes the tenant's kind, so a row can name
  a demo workspace only. Password sign-in (`resolve_tenant_for_login`) and `provision_user` reach regular workspaces only.
- **Entry.** `GET /demo` (one sentence, one button) and `POST /demo`: no field but the CSRF token; `demo_claim` hands out the oldest fresh
  ready workspace (SKIP LOCKED); the principal is read back from the seller row as sign-in does; new session id and CSRF token;
  `DEMO_ENTERED` audit row; the session ends with the lease (30 minutes). A visitor already in a workspace is sent back to it (D22).
  Limits: the page per address, the claim per address and in total, scenario steps per workspace. The client address comes from
  `app.security.trusted-proxy-hops` (0 to 2, default 0).
- **Input.** The scenarios page and its steps take a scenario and a step from fixed lists, never text; every write goes through the
  ingestion services as the workspace's seller. A demo workspace refuses phone numbers outside `+999` and holds at most 300 orders and
  payments (application sentence, database trigger).
- **Upkeep.** `demo-init` and `demo-refresh` (once, or `--every 60s..3600s`) on the demo operator's login (EXECUTE on the demo functions
  only) and the application login for the seed. Retire disables the workspace and overwrites raw text, names, phone hashes, last four
  digits, line labels and delivery notes; the ledger, the audit log and the flags are untouched. The entrypoint
  (`docker/demo-entrypoint.sh`) runs the loop as a second process; the web process never holds the operator's login.
- **Size** (measured on the test container): about 130 rows and 100 KB a seeded workspace, about 240 rows and 150 KB after a visit.

### Trade-offs and weakened defenses (task 9): register items 111 to 129, below.

## Testing strategy

- Unit tests for parsers, scoring, and each suspicion rule, with a table of examples.
- **Property-based tests** (jqwik):
  - any sequence of postings leaves every transaction balanced;
  - applying the same message twice changes nothing (idempotency);
  - matching never assigns more than the payment amount or the order balance;
  - reversal followed by re-post returns to the same balances.
- Integration tests on PostgreSQL for tenant isolation and immutability.
- One end-to-end test per demo scenario.

## Deployment

Docker image, PostgreSQL, health endpoint, migrations with Flyway. Target a free
container host and confirm its limits (memory, sleep behavior) before the final week.
The demo credentials go in the Devpost testing field.

## Risks

- Real operator SMS formats vary: mitigated by data-driven templates.
- Free hosting may sleep: mention it in the testing notes and keep a short warm-up.
- Scope: the board, simulators, and ledger must work end to end before any extra feature.
| 14 | The per-account sign-in limit can be used to lock a real user out for up to 15 minutes (anyone who knows a workspace code and email can spend the five attempts) | The alternative is no throttle on guessing; a hard lockout would be worse for the same reason | Tokens return over time (one per 3 minutes), the limit is per account and does not touch others, a successful sign-in resets it, and the operator can see the pattern in the security log (`rate limit hit`); no permanent lockout |
| 15 | The sign-in limits count the socket address, not `X-Forwarded-For` | Trusting the header blindly would let anyone choose their own key | Test proves a forged header does not change the key; behind a proxy all users share one address until deployment names the proxy (task 13) and the per-IP numbers are revisited |
| 16 | Chunked and form bodies up to the limit are held in memory (at most 64 KiB, 16 KiB for JSON, 4 KiB for login) | A check on `Content-Length` alone is bypassed by a body with no length | Hard caps, 413 before the application sees anything, concurrent requests bounded by the container's thread pool |
| 17 | Cache-Control is the framework's `no-store` for every response, static files included | There are no static files yet (task 8) | Task 8 must serve versioned static files with their own cache headers and add a test |
| 18 | Raw non-ASCII bytes and control characters in forms, queries and JSON are refused outright | "Reject, do not guess": servers otherwise repair them silently and two parts of the system then disagree | A client that sends them gets 400; ordinary browsers never do |

| 19 | Raw message text is stored verbatim in `orders.raw_text` and holds names and phone numbers | Needed for audit and requirement 1.5 | Tenant isolation, never logged, never printed by any value type, escaped on output (tested end to end), deletion by requirement 9.6 in a later task (the column is NOT NULL, so deletion will blank it) |
| 20 | Phone hash is HMAC under a key derived from `app.secret`, not Argon2 and not a salt in the database. If the secret and the database both leak, numbers can be recovered by brute force; rotating the secret changes every hash and needs a rehash job (not built) | A phone number has about 10^9 possibilities, so any hash of it is brute-forceable by someone who holds everything needed to recompute it; the key is the only thing kept out | Key never in the database, per-tenant keys, the number itself never stored, last 4 digits only on display. V003's comment is corrected by `COMMENT ON COLUMN` in V014 |
| 21 | Same number with and without a country code is two customers; customers without a phone are not merged | No country logic, and no safe key without a phone | Named in the code and tests; tasks 5 and 6 reuse the same function |
| 22 | Only two message shapes are understood and one unrecognised line (even a greeting) makes the whole message `UNPARSED` | Money must not depend on a loose pattern | The message is kept and shown, never dropped; the optional AI extractor (task 10) is the planned improvement |
| 23 | "The amount is written in the message" proves presence, not meaning: a price equal to the digits of a phone number would pass | The check is deliberately independent of the parser | The stated-total-versus-lines check, `NEEDS_REVIEW`, and the seller's last word |
| 24 | Input with bad encoding, NUL, control characters, lone surrogates or over 4,000 characters is refused and not stored (`REJECTED_INPUT`) | PostgreSQL cannot store NUL and such text cannot be stored safely; this is the owner-approved reading of requirement 1.5 | The caller still holds the text and receives a code saying why |
| 25 | The application role gains INSERT on `orders`, `order_line`, `customer`; the catalog is read-only for it | Ingestion must write; sellers cannot yet edit prices | No UPDATE or DELETE (tested), RLS `WITH CHECK`; the demo seed (task 9) and an operator fill the catalog; a status UPDATE will be a separate named grant in task 6 |
| 26 | Hard caps: 4,000 characters, 80 lines, 50 items, quantity 9,999, 10^12 minor units, label 100, name 100, note 300 | Bounded work and no overflow | Named constants (`OrderLimits`); larger input becomes `UNPARSED`, not an error |
| 27 | `externalId` is limited to `A-Z a-z 0-9 . _ : -` and 128 characters | It is stored, indexed and used in lookups | Widen the set on request, never by dropping validation |
| 28 | The new CHECKs on existing tables are `NOT VALID` | Those tables may hold rows from before | New and changed rows are checked; `VALIDATE CONSTRAINT` once old data is known to fit |
| 29 | A replay with different text keeps the first order | Requirement 1.3 | The caller is told (`DUPLICATE_DIFFERENT_TEXT`), a fixed-label event is logged, nothing is overwritten |
| 30 | The pathological-input test has a wall-clock bound, which can be flaky on a slow machine | Timing is the only direct measure | Generous bound plus a deterministic structural scan: the extraction code cannot build a regular expression, format string, class or process from text |
| 31 | PostgreSQL's own server log can contain the failing row of a constraint violation (message text, names, hashes) and, depending on settings, bound parameters | The application cannot control the database server's log | The application log carries ids and codes only (tested). Deployment (task 13) must set `log_error_verbosity = terse` and `log_statement = none`; the item is in `tasks.md` task 13 |
| 32 | The authenticity of an SMS cannot be verified from its text: a forged text in the exact provider shape looks real | There is no signature on an SMS | The system never moves money; no transaction id gives `UNVERIFIED` (never auto-matched, task 6); a repeated id gives `DUPLICATE`; the task 7 flags; the seller has the last word; the machine feed needs the HMAC signature (task 11) |
| 33 | Raw text is stored verbatim in `payment.raw_text` and holds names and numbers | Needed for audit | Tenant isolation, never logged, never printed by a value type, escaped on output (tested), deletion by requirement 9.6 in a later task |
| 34 | **Templates are less flexible than patterns.** No optional parts and no alternatives in one template (a text that sometimes has a reference is two templates); the delimiter rule forbids a literal that starts with a character the hole allows; transaction ids and references read only letters, digits, `_`, `-` (a `.` or `:` in one cannot be read from an SMS although the database accepts it); a currency hole reads letters only (`F CFA` is literal text); some real formats will need a new scanner feature | The owner chose a linear scanner of our own over a regex engine: no new dependency, no backtracking, no engine to patch | Two templates for two shapes, the ambiguity rule, a clear refusal at startup naming the problem code, examples and counter-examples that prove each template, `UNPARSED` and the seller's last word for anything unread |
| 35 | Templates ship with the release; the original design said they could be added without one | Nothing from outside the build reaches the scanner at runtime | A `PaymentTemplateSource` interface; a database source can follow with the same gates; a new provider format needs a pull request |
| 36 | Numeric dates only; one fixed UTC offset per template; no daylight-saving | Month names and zones are locale and rule dependent | The template is refused at load rather than half working; the target region has no daylight saving; other regions need a template with the right offset |
| 37 | Timestamps more than 15 minutes ahead or 35 days back are `UNPARSED` (`DATE_IMPLAUSIBLE`) | A forged or stale confirmation must not look current; the simulators (task 9) must generate dates in the window | The message is kept and visible; the numbers are named constants (`PaymentLimits`) |
| 38 | Ambiguity refuses: a message matched by two templates with different results becomes `UNPARSED` | Template order must never decide money | The build-time proof makes ambiguity a development error; identical results are accepted |
| 39 | Duplicates are stored, so a replayed valid SMS under new external ids adds `DUPLICATE` rows | Requirement 2.4 | The per-tenant ingestion rate limit bounds growth; each row is at most 1,000 characters; the rows are the evidence for the duplicate flag |
| 40 | The transaction-id uniqueness is a partial unique index (`DUPLICATE` rows excluded) | A plain constraint would refuse the duplicate record | The index still forbids two non-duplicate rows with the same id (tested under 16 concurrent submissions); a CHECK ties `DUPLICATE` to a non-null `duplicate_of`; a composite foreign key keeps the link inside the tenant |
| 41 | The application role gains INSERT on `payment` | Ingestion must write | No UPDATE or DELETE (tested), RLS `WITH CHECK`; status changes will be a separate named grant in task 6 |
| 42 | New CHECKs on the existing `payment` table are `NOT VALID` | The table may hold rows from before | They apply to every new and changed row; `VALIDATE CONSTRAINT` once old data is known to fit |
| 43 | Hard caps: 1,000 characters per SMS, 20 templates, 500 characters per template, 10 holes, 12 literal characters, 8 KiB per file | Bounded work | Named constants (`PaymentLimits`); larger input is refused or becomes `UNPARSED` |
| 44 | Free-typed text and screenshots stay `UNPARSED`; there is no OCR (requirement 2.5 mentions screenshots) | Only deterministic templates exist until task 10 | The text is kept; `UNVERIFIED` is reached only by a template that declares no transaction id |
| 45 | A sender name is free text from an SMS and could be forged | Nothing verifies it | Never a key or an identity, length-bounded, escaped on output, advisory in tasks 6 and 7 |
| 46 | A sender number written with and without a country code is two hashes, so the payer lookup may miss; no customer is created from an SMS | No country logic; a forged SMS must not add people | The same function and key as orders (item 21); the miss is predictable; the seller can confirm by hand |
| 47 | Transaction ids are compared in upper case: `pa1x2y3z` and `PA1X2Y3Z` are one transaction, so a replay with the case changed is a `DUPLICATE`. The cost is a false duplicate if a provider ever issues two ids that differ only by case | Providers' ids are tokens a human retypes or a forwarder re-cases; the safe failure is a duplicate the seller can see, not a second payment | The unique index is on `(tenant_id, provider, upper(tx_id))` and the lookup uses `upper(tx_id) = upper(?)`; the id and the whole text are stored as written; tested sequentially, in both directions, and with 16 concurrent messages in two cases |
| 48 | "The amount is written in the message" proves presence, not meaning (item 23) | The check is independent of the parser | The template's fixed wording around the amount, the stated currency, the task 7 near-miss rule, the seller's last word |
| 49 | Reference hole values are stored as written and compared by a key function (strip, upper-case); task 6 must use `ReferenceKeys` for every comparison, or `ord-1` and `ORD-1` will not match | One format with `orders.order_ref`, no data migration | A test of the key function; the task 6 line in `tasks.md`; a functional index on `upper(...)` can be added when the matching query exists |
| 50 | Inherited: item 31 (PostgreSQL server log), item 5 (in-memory rate limiter), item 1 (the tenant setting protects against our own bugs, not SQL injection) | See those items | See those items |
| 51 | **No signal derived from an SMS is unforgeable.** Anyone who can write the confirmation can write its amount, reference, sender and time | The text is all the system has | An automatic match needs seven floors and a lead over every other candidate, not only a score; `UNVERIFIED` and a helper's payment are never automatic; a match moves no money; the kill switch; audit; the seller reverses (stop 3); task 7 flags will veto |
| 52 | The application role gains `INSERT` on `match` and column-limited `UPDATE` on `orders.status`, `payment.status` and six `match` columns | Matching has to change a status and record a decision | Column lists, no `DELETE` or `TRUNCATE`; transition triggers allow only the moves the matcher makes (never `DUPLICATE` or `UNPARSED` to `MATCHED`, nothing into or out of `NEEDS_REVIEW`, `UNPARSED`, `CANCELLED`); deferred consistency triggers; a catalog-driven test lists every column and its privilege |
| 53 | Nothing can move an order out of `NEEDS_REVIEW` yet, for anyone | The transition trigger covers every role; task 8 must add its own named move | Task 8 line in `tasks.md`; until then such an order cannot be matched |
| 54 | The match-to-posting rule (amounts and accounts) is enforced by a trigger and tests, not by the ledger, which knows nothing about matches | The ledger stays generic and append-only | Deferred trigger at commit; the account codes in the trigger and in `ChartOfAccounts` are kept in step by a test |
| 55 | Name similarity is a heuristic; two people with the same first name and initial agree weakly | Names are free text | At most 12 points (15 with last four), never alone, bounded work, no regular expression; the neutral name never scores |
| 56 | Matching settings are configuration | The owner asked for thresholds as configuration | Hard bounds checked at startup, a gap of at least 10, floors that no setting can relax, a kill switch that defaults on |
| 57 | The four accounts are created by the application role at first use | A tenant has no chart of accounts otherwise (provisioning does not create one) | Fixed codes, unique per tenant, idempotent; the role already held `INSERT` on `account`; a wrong type under one of the codes stops posting |
| 58 | Postings made on the caller's connection do not go through `LedgerService` | A posting must commit with the match and the status change | One shared check class used by both paths; a reversal cannot be posted this way (until the unmatch flow adds its own method); the deferred balance trigger still fires |
| 59 | The new CHECKs on `match` are `NOT VALID`; the transition triggers apply to every role, including the owner | The table may hold rows from before; a status move must be deliberate | New and changed rows are checked; `VALIDATE CONSTRAINT` once old data is known to fit |
| 60 | The post-commit trigger is best effort. A payment or order that was stored but whose matching failed (or was never run) stays unmatched until the next event for the same candidates, or until the stop 3 `rematch` | The ingestion transaction must not depend on matching, and a match failure must not lose a payment | The failure is logged with ids only; `apply` is idempotent in effect (unique indexes); nothing is lost, only delayed |
| 61 | The recorder (trust) comes from the audit trail, and the application role can append audit rows. A later forged `PAYMENT_INGESTED` entry could claim a seller | There is no other record of who ingested a payment | The earliest entry of the tenant wins, so a forged one cannot raise trust; no entry means helper; a role other than the exact words means helper. Not defended: a forged entry written before the real one (needs the application role's privileges at the moment of ingestion) |
| 62 | `ORDER_ACCEPTED` is posted at ingestion, so a posting failure fails the order's ingestion | The order is a receivable from the moment it is accepted (requirement 4.1), and an order without its posting cannot exist | The caller sees a clear exception, nothing is stored, a retry is safe and cannot post twice. A `NEEDS_REVIEW` order is posted later (task 8); `apply` posts it if it is still missing |
| 63 | A stale suggestion can be stored for a payment that was matched meanwhile (the suggestion is written after the engine's read, outside the apply transaction) | Suggestions change nothing and need no lock | A confirmation re-reads everything under the lock and refuses a payment that is no longer free; the other suggestions of a matched payment are retired by the match |
| 64 | The tie margin between candidates is evaluated on the engine's read, not again under the lock | The lock covers the two rows, not the other candidates | Under the lock every floor and the threshold are re-checked; the margin is the only part decided on the unlocked read, and a match moves no money and can be reversed |
| 65 | `apply` locks the payment first and the order second, in every path. A different order in any new code could deadlock | Two orderings are possible and one is needed | One place (`applyWithin`); a test with 16 threads in random pairs finishes without a deadlock; swapping the order in both statements consistently is an equivalent change and is not defended |
| 66 | The index tests pin the definitions of the four indexes and do not claim that the planner uses them | A plan on a nearly empty table says little and changed as soon as other tests had written rows (the first version of the test failed for that reason and was removed) | A dropped, re-columned or non-partial index fails; whether a query is fast at scale is not tested |
| 67 | **A missing actor is a helper, never the machine.** Any caller that passes a NULL actor (a bug, a script) gets suggestion-only matching, and the stored word `MACHINE` is not trusted either | The first version read a NULL actor as the machine feed, which fails open: one forgotten argument gave the highest trust, and the signed feed does not exist before task 11 | Both recorder subselects return the user's role or `HELPER`; `recorderOf` trusts only the exact word `SELLER`; `Recorder.MACHINE` is unreachable from storage and from tests of ingestion. Task 11 must record an explicit machine identity in the audit entry and add its own branch (line added to `tasks.md`) |
| 68 | **Seller-only decisions are enforced by the service, not by the database.** The application's database role is the same for every user and may update a status or a match; the database cannot tell a seller from a helper | Roles live in `app_user`; PostgreSQL roles are per application | Every decision begins by asking the database whether the user is an enabled seller of the tenant (role, `disabled_at`, tenant), tested for a helper, a disabled seller, a seller of another tenant and an unknown user, for every action; the transition and commit-time triggers keep the books consistent whoever calls; who and when are audited; task 8 adds the permission and CSRF checks at the HTTP edge as a second wall |
| 69 | **The seller's no is permanent for the engine.** A rejected suggestion, a reversed match and the suggestions of a payment set aside are never proposed or applied automatically again | The seller must have the last word; an engine that re-proposes or re-applies a refused pair is a worse defect than a missed match | The seller can always match the pair by hand; a "forget my decision" action is possible later |
| 70 | `rematch` has no rate limit of its own and looks only at the `candidateCap` most recent free payments | It is a service call; there is no endpoint yet | Bounded work per call; older payments are matched by their own events or by hand; task 8 must rate-limit the endpoint (line added) |
| 71 | An unmatch reverses the match's posting and leaves `ORDER_ACCEPTED`; a reversed excess removes the customer credit | Requirement 4.3: nothing is edited or deleted | Nothing consumes the credit yet; when it is applied or refunded (a later feature) an unmatch of that match must be refused or compensated, and the design for that feature must say how |
| 72 | The apply step now locks the open suggestion of the pair (`FOR UPDATE`) after the payment and the order; a rejection locks only the match | A confirmation racing a rejection must wait and then refuse cleanly, not fail half way | One lock order everywhere (payment, order, match), checked by looking at what is locked at each moment (`LockOrderIT`); 16-thread and confirm-versus-reject tests |
| 73 | The property tests are a statistical argument: 30 and 20 sequences of 20 steps against a real database, with coverage thresholds so that they cannot pass by doing nothing | Every step is a round trip; about 90 seconds | Invariants are read from the tables independently of the service; a coverage rule fails the run if a flow is never exercised; a failure shrinks to a short sequence. They cannot show absence of a defect, and a coverage threshold can fail on an unlucky draw (rerun) |
| 74 | **Every row lock the matcher takes, in code and in the V016 commit-time trigger, is `FOR NO KEY UPDATE`, not `FOR UPDATE`, because of a deadlock the storm test found in stop 2b's code.** Inserting a match takes a key-share lock on the order and the payment it refers to; `FOR UPDATE` waits for a key share, so the engine storing a suggestion and a seller's decision could wait for each other for ever (PostgreSQL aborted one with `deadlock detected`) | The matcher never changes a key column, so the weaker lock serialises its own operations just as well | No argument about cycles is needed any more: no lock in the matcher or its trigger functions waits for a key share. `ForeignKeyChecksDoNotBlockIT` holds a key share on the order and the payment and requires an apply and an unmatch to take their locks **and commit** anyway (a mutant that puts `FOR UPDATE` back in the trigger makes it time out); the storm test of 16 threads, with arrivals, runs against it. V016 was edited in place (it is not merged); a database that already ran the old V016 needs its checksum repaired or a rebuild |
| 75 | **Every time written to the database is cut to microseconds in one place (`DbTime`)** | PostgreSQL keeps microseconds and rounds; a Linux clock has nanoseconds, a Mac's does not. A time stored as it came back a microsecond different on CI from the one the service used, and the difference could not show on the developer's machine | A unit test fails if any other class turns an instant into a database value; the integration fixture's clock carries nanoseconds on every machine; a mutant that stops truncating is caught by five tests. Not covered: times the database itself writes (`now()` defaults) are microseconds already; the order's `ORDER_ACCEPTED` posting is stamped with the machine clock, not the injected one |
| 76 | **A third-party script (htmx 2.0.11) runs in every signed-in page** | It is the largest piece of code we did not write | Served from our origin under a hashed name, SHA-256 and size pinned by a test against the release asset, licence (0BSD) read from the release archive, `script-src 'self'`, eval and script tags in responses off, own origin only; every action also works without it (tested in a browser with JavaScript disabled). Not covered: a defect inside htmx itself |
| 77 | **The application role may `UPDATE (total_minor)` on `orders`** | A `NEEDS_REVIEW` order needs its total from the seller | Column-limited grant (catalog test); a trigger refuses any change of the total outside `NEEDS_REVIEW` to `PENDING_PAYMENT`, for every role including the owner; a deferred trigger refuses that move without this tenant's `ORDER_ACCEPTED` posting at commit. **Deviation from the design text:** the audit row `ORDER_APPROVED` names the order only (the table has no detail column); the total is in the posting |
| 78 | **A seller can state any total for a `NEEDS_REVIEW` order and it cannot be corrected afterwards** (D7) | No cancel or correction exists before the later task | Review page with the message, then a second page with the amount as it will be posted; seller only; bounded 1 to 10^12; audited; posted, so a later reversal is possible (task 12 line) |
| 79 | **The raw text of a message, which can hold a full phone number, is shown to the seller** (D10) | The seller must see what the parser could not read | Seller only, only on unread, review and flagged items, in a closed `<details>`, never a link; digits joined by up to three separators (space, dot, dash, parentheses, no-break spaces) that make 7 or more keep their last four. A heuristic: it also masks dates such as `2026-11-03` and misses numbers in words or broken by letters. A helper never receives the text (the repository is not even asked) |
| 80 | **Display cleaning changes what is shown, not what is stored** | Bidirectional overrides and zero-width characters can disguise a name | One function with a test per character class; the stored value is untouched and exported through CSV neutralisation |
| 81 | **CSV neutralisation alters cells**: a text cell that starts with `= + - @ |`, their full-width forms, or a tab, CR or LF gets a leading apostrophe | Otherwise a spreadsheet may run a stranger's formula | Said on the download page; numbers the program wrote are never prefixed (tested unquoted); every corpus string and formula payload read back with an independent reader |
| 82 | **The books check is cached for five minutes per tenant** | A live check at every board load would cost every user | "Check again now" forces a run; a mismatch is logged at ERROR with fixed labels and shown ("Books: needs attention") until a check passes; never hidden or fixed by the page. Not covered: the cache is in memory and lost on restart |
| 83 | **The Flagged column is partial until task 7** | No suspicion rule exists yet | Repeats and unverified payments by status, plus any `flag` rows; the column says so in one sentence; the board unions both sources so task 7 changes no page |
| 84 | **No automatic refresh** | Polling costs battery and data and can move a card under the seller's thumb | A Refresh link; actions update what they touched |
| 85 | **The new limits are in memory, per process, and are token buckets** (inherits item 5): the hour limit on rematch holds 20 and refills one every three minutes, so it is not a fixed window | One instance until task 13 | Bounded key tables; startup validation; tested as wired (`BoardRateLimitsTest`), and every POST route tested at the endpoint (`BoardActionLimitIT`) |
| 86 | **A helper sees customer names and amounts on cards** | They record the sales | No phone beyond the last four, no raw text, no money totals in the summary, no books, no downloads, no decision buttons (each refused with 403 at the endpoint and absent from the rendered page) |
| 87 | **The phone layout depends on CSS scroll-snap** | No script needed for swiping | The chips are plain anchors and each column is a real section; without the CSS the sections stack |
| 88 | **No cancel, dismiss or correct-total** (D7) | Out of scope for task 8 | Records are paged by time; a later task line in `tasks.md` |
| 89 | **English only** | One locale until French strings are written | All text is in `messages.properties`; the locale is fixed and a French file can be dropped in |
| 90 | **A developer tool pulls npm packages** (puppeteer-core 25.16.0 and 24 dependencies) | A real browser check needs a browser driver | Outside the Maven build, the runtime and CI; pinned with a lock file; installed with `npm ci --ignore-scripts`; uses the installed Chrome (no download). Not covered: the npm packages are not in the CI vulnerability scan |
| 91 | **Eight redundant guards were deleted**, each found by a mutant that no test could kill: a status predicate in `OrderRepository.approveWithin` (the caller has locked the order and checked the status in the same transaction, and the triggers refuse any other move), the length checks before the two date parsers (a longer form the ISO parser accepts is a year above 9999, refused by the range), the length check of the cursor parser (the underscore position and the 36-character id bound it), the length check of `Notice.parse` (exact equality with a name), the cap on counted line breaks in `Display`, `scale == 0` in `MoneyFormat.parse` (a decimal then has too many digits anyway), and `reverses_txn_id IS NULL` in the books check (a reversal has its own kind) | A guard no test can tell apart from its absence is noise that looks like a defense | Each named here; the remaining guards are each killed by a test |
| 92 | **"Today" on the board and the summary comes from the application clock; a row's time from the database clock** | Both are UTC on one host | On one host they agree; the tests ask for each figure on the day its row has, because the shared test clock moves |
| 93 | **htmx keeps one key in `sessionStorage`** (`htmx-current-path-for-history`, the current path), even with history off | It is how htmx 2 works | Only a path, no page content; checked in the browser walk |
| 94 | **Refused htmx requests show the plain error page's sentence in the notice area** | htmx drops error answers by default | `responseHandling` swaps `#notice` from the error page; the error page keeps it (lint) and says nothing about the cause; checked in a browser with a 404 |
| 95 | **Test contexts use four database connections each** | Spring keeps every test context alive on one shared database | Test-only setting; no web test sends requests in parallel; production pool settings unchanged |
| 96 | **The application role may now write `flag`**: INSERT, and UPDATE of `resolved_at` and `resolved_by` only | The rules and the resolve button need it; the table was SELECT-only until task 7 | Column grant; typed foreign keys to the same tenant; shape and bound CHECKs; a trigger, for every role including the owner, refuses any other change, a second resolution, a delete and a truncate (`FlagSchemaIT`) |
| 97 | **The rules are heuristics**: they miss some fraud and flag some honest payments | Text from chats and confirmations proves nothing by itself | Each flag is advisory and explained in one sentence; it only removes automatic matching; the seller confirms, matches by hand or marks it checked (D8). False-positive rates are not measured |
| 98 | **R6 and R10 need a full sender phone**: a forward with only the last four digits is not counted as a burst or an alias | There is no phone hash to compare | Such a payment is already flagged by R2 (no transaction id) and is suggestion-only |
| 99 | **Thresholds are global**, not per workspace | No settings page yet | Bounds checked at startup; `app.suspicion.disabled` switches a rule off for all; per-workspace settings are a later task |
| 100 | **Any flag, even a checked one, keeps a pair from automatic matching** (D3) | A checked flag says the seller looked, not that the pair is safe | Costs the seller one confirmation per flagged pair; the explainer says why ("A flag was raised on this payment, this order or this pair") |
| 101 | **`reason` repeats the code's sentence** | The row stays readable without the code table | A test compares every stored sentence (and the back-fill's) with `FlagCode.sentence()` and with the page text |
| 102 | **The rules do not run again on old records** | Running them on history would flag records the seller has already handled | Only the back-fill of `DUPLICATE` and `UNVERIFIED` payments (V019); new records and every matcher decision are evaluated |
| 103 | **Two `EXISTS` on `flag` in each matching facts query** | The scorer's floor needs the subjects' own flags | Partial indexes per payment and per order; the cost on a large workspace is not measured |
| 104 | **Payments from one sender phone are recorded one at a time** (`pg_advisory_xact_lock` on tenant and phone hash) | Under READ COMMITTED sixteen payments sent at once each saw only committed ones, and a burst slipped under R6 (4 flagged instead of 13) | The lock is per tenant and phone, held to commit; other senders are not delayed; `MatchingConcurrencyIT` asserts exactly 13 |
| 105 | **Redundant guards deleted after the first mutation pass**, each a mutant no test could kill: in R3 the equal-digit skip and the length check (the exact amount is refused first); in R11 `paid <= balance` (the excess is then below any minimum); `isBlank` in `RuleNames` (a blank key is empty); the `UNPARSED` short-circuit in `SuspicionWriter` (no rule fires on an unread payment); the locked facts' own flags in `applyWithin` (the wall reads all three scopes); the Java "already resolved" check in `FlagService` (the UPDATE's `resolved_at IS NULL` and the trigger); `subject_type` in the unique key (`chk_flag_subject` makes it follow from `payment_id`); the "customer existed" flag in `OrderContext` (a new customer stores the parsed name, so they agree) | A guard no test can tell apart from its absence is noise that looks like a defense | Each named here; every remaining guard is killed by a test |
| 106 | **The tenant predicates in the board's flag reads are redundant** with row-level security and the composite foreign keys | Defense in depth | Kept, and made testable: a flag of another tenant naming our records, written with the keys off, is not shown when the statements run as the owner (`BoardRepositoryIsolationIT`) |
| 107 | **A flag can never be deleted**, which will conflict with deleting a workspace's data (`DELETE_TENANT_DATA`, a later task) | Write-once is the audit property | Open: that task has to decide how flags go with the rest of the data (for example an owner-only routine that is allowed past the trigger) and name it as a weakened defense |
| 108 | **`app.suspicion.disabled` (a list of codes) instead of one `enabled` key per rule** | One setting, validated against the enum at startup; a typo stops the application | A small deviation from the design stop's wording; the switched-off codes are logged at startup |
| 109 | **Test fixtures share one set of phone keys** (`MatchingFixture.SHARED_KEYS`) | Two fixtures hashed one phone differently and R7 fired in tests that were not about it | Test-only; the production hashing (per-tenant salt) is unchanged |
| 110 | **The older concurrency tests now give each payer its own phone** | Sixteen payments from one phone are a burst (R6), which those tests are not about | A new test asserts the burst count for sixteen at once from one phone |
| 111 | **A public endpoint signs a visitor in without a password** (`POST /demo`) | Judges open the URL with no account (requirement 7) | Off by default and only on a database in DEMO mode (startup refuses otherwise); reaches DEMO workspaces only, through `demo_claim`; CSRF, per-address and global limits, session fixation protection, an audit row, a 30-minute lease; password sign-in refuses demo workspaces, so the doors never cross (`DemoEntryIT`, `DemoSchemaIT`) |
| 112 | **The application role may call `demo_claim`, which writes a row it cannot otherwise write** | The web process must lease a workspace | It changes only `demo_tenant.state`, `claimed_at` and `lease_ends_at` of one READY demo workspace, only in DEMO mode; the application still creates no tenant and no user |
| 113 | **Nine new `SECURITY DEFINER` functions** | The pool must be changed without table privileges for anyone | Each has a fixed `search_path`, checks the mode and the workspace's kind itself, is on the guard's allowlist and has exactly one grantee (`ProvisioningIT`, `DemoSchemaIT`) |
| 114 | **Two tables outside the per-table isolation probe** (`deployment_mode`, `demo_tenant`) | One has no tenant column; the other's rows can only name demo workspaces, which the probe's seeds are not | Row-level security on both; no grant to the application on either; named on the coverage allowlist with the reason; behaviour proven in `DemoSchemaIT` |
| 115 | **A new role, `chatledger_demo_operator`, and its login** | Seed and reset must not run as the web process or the owner | EXECUTE on the demo functions only, no table privilege (tested); its login exists only where `DEMO_DB_USER` is set; the web process is started without it |
| 116 | **Visitors act as sellers** | The demo must show confirm, undo, resolve and export | Only in their own leased synthetic workspace; the row cap, the ingestion, action and step limits apply |
| 117 | **Free text in a demo may carry real personal data** | No rule can recognise a real name in text | Parsed phones outside `+999` are refused; only the visitor who typed it sees it, for at most the lease plus one refresh period; retire overwrites it |
| 118 | **Retired workspaces stay in the database** | The ledger, the audit log and the flags are never deleted | Disabled and overwritten; growth bounded by the claim limits and the row cap; a full wipe is dropping the demo database (task 13 runbook) |
| 119 | **The ingestion services know about demo workspaces** | The synthetic-phone rule and the row-cap sentence | One read with the tenant's money settings; a regular workspace never pays for the counts (CASE); tested on both kinds |
| 120 | **Every order and payment insert runs a trigger that looks up its tenant's kind** | The row cap must hold for every path | One primary-key lookup for a regular workspace; the counts run for demo workspaces only; cost not measured on a large workspace |
| 121 | **The per-address limits trust `X-Forwarded-For` when `app.security.trusted-proxy-hops` > 0** | Behind a proxy every visitor shares one socket address | 0 by default (header ignored, tested); bounded to 2 at startup and named in the startup log; only the entry that many hops from the right, and only an IP literal, else the socket address. A wrong value for the host lets a client choose its key; task 13 sets and checks it |
| 122 | **The reset depends on a process outside the web application** | Privileged upkeep must not run in the web role | The entrypoint runs the loop; the host's scheduled job is the fallback (task 13); if neither runs, the pool drains and `/demo` says "all in use", never anything less safe |
| 123 | **A demo script waits in the visitor's session between "Send the order" and "Send the payment"** | The payment must match the order just sent | Server-side session only; texts the application generated; at most seven; gone with the session |
| 124 | **The seed's catalog JSON is built by string concatenation in Java** | `demo_seed_catalog` takes one JSON argument | Only the application's own constants; values escaped; the database's CHECKs bound each value; a prepared statement carries it |
| 125 | **`deployment_mode` blocks provisioning with a row lock read** | `provision_tenant` and `demo_init` must not both pass | `FOR SHARE` against `FOR UPDATE` on one row; a provisioning call waits for a concurrent `demo_init` at most |
| 126 | **The demo seller's password is random and thrown away** | No credentials may exist | A hash in `app_user` that nobody can match; password sign-in refuses demo workspaces anyway |
| 127 | **The browser walk and the fixture run outside CI** | They need Chrome and a running server | `DemoBrowserFixtureIT` is skipped without `-Dbrowser.demo=true`; the walk's checks are listed in the notes |
| 128 | **Size on a free tier is estimated, not measured** | The host is chosen in task 13 | Measured on the test container (`DemoSizeIT`); the operator watches `pg_database_size` and drops the demo database before 80% of the tier |
| 129 | **Four redundant guards were deleted**, each a mutant no test could kill: the Java check that a leased workspace is a demo one (`demo_claim` reads only `kind = 'DEMO'`, and a `demo_tenant` row can name no other kind); `disabled_at` in `demo_claim` (a READY workspace is never disabled: retire disables and retires in one transaction, and the state never goes back); the `enabled` field of `DemoProperties` (the switch is read where it acts); `tenant_kind` in the forward-only trigger (its CHECK keeps it DEMO) | A guard no test can tell apart from its absence is noise that looks like a defense | Each named here; every remaining guard is killed by a test |
