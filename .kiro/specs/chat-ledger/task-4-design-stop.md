# Task 4 design stop: order extraction

Status: **approved by the owner** with all nine recommendations accepted and six changes (below). Implementation follows this text.

## Owner's six changes (override anything below that disagrees)

1. **Phone canonical form.** One function, `PhoneNumbers.canonicalDigits`, turns a phone number into canonical digits: digits only, a leading `+` or `00` removed, no country logic. The HMAC and `last4` both use it. Limit, named: the same number written with and without a country code gives two customers. Tasks 5 and 6 must use the same function to find a payer from an SMS (added to `tasks.md`). Tested on several spellings.
2. **Validator versus catalog.** "Every amount appears in the source" applies to amounts the **parser read**. A total computed from the catalog is not in the text: the service computes it with `Math.*Exact`, caps it, and does not require it to appear in the source. Both cases are tested, including a mutation that lets a parser amount through unchecked.
3. **`currency` is `NOT NULL` too.** `UNPARSED` and `NEEDS_REVIEW` store the tenant's currency. Only `total_minor` is nullable, with the CHECK tying it to the status.
4. **French input.** `FCFA`, `F CFA` and `XAF` are accepted as the tenant's `XAF`. French label aliases (`Nom`, `Telephone`/`Téléphone`, `Livraison`, `Commande`) are constants. Still no pattern built from input. French synthetic examples are added to the tests and the hostile corpus.
5. **Source scan.** The existing scanner gains a rule: no unit test (any `*Test.java` that is not `*IT`) may reference `LedgerDatabase` or Testcontainers. Mutation-checked, and added to the list of what the unit phase proves.
6. **Demo catalog seed.** Added to task 9 in `tasks.md`, because the catalog is read-only for the application role. The CSV formula injection line is kept in the export task.

Changes found necessary while implementing (reported in the summary): `orders` gets `order_ref` and `delivery_note` columns (requirement 1.1 has nowhere to store them otherwise), and `order_line.unit_minor` becomes nullable like `total_minor` (an unknown price must not be stored as 0). The V003 comment cannot be edited (applied migrations are never edited), so V014 sets `COMMENT ON COLUMN` instead. The one-line example "call ending 1234" is dropped: four digits are not a canonical number and cannot be hashed.

Sources re-read: requirements 1, 8, 9, 10; design (pipeline, data model, API, tenancy); tasks 4.1 to 4.3; migrations V003, V004, V010, V011; the `extraction` and `ingestion` modules.

## What exists today (facts checked in the repository)

- `extraction` has only value types and an interface: `ParsedOrder`, `ParsedPayment`, `MessageParser`, `ExtractionResult`. There is no parser.
- `ingestion` has only `IngestionRequest(externalId, text)`.
- The tables `customer`, `orders` and `order_line` exist with row-level security, but the application role has **SELECT only** on them (V010).
- There is **no catalog table** anywhere. Requirement 1.2 needs one.
- `orders.total_minor` and `orders.currency` are `NOT NULL`, so an `UNPARSED` order has nowhere to say "no total".
- `customer.phone_hash` has a comment saying "BCrypt/Argon2 with per-tenant salt", but `tenant` has no salt column.
- The tenant-aware JDBC code (`TenantScope`) lives in `web`, not in `ingestion`.

## What I will build

| # | Piece | Where | Notes |
|---|---|---|---|
| 1 | `TextNormalizer` | `extraction` | Works on a derived copy only: Unicode NFKC, strips bidirectional-control and zero-width characters, maps non-ASCII digits to ASCII, collapses whitespace. The stored raw text is never changed. |
| 2 | `OrderTextParser` | `extraction` | Hand-written linear scanner. No regular expression is built from input, no backtracking. Two input formats (below). Anything else is `UNPARSED`. |
| 3 | `OrderValidator` | `extraction` | Last gate on the parser's own output: bounds, currency, "every money amount appears in the source text", lines against total. |
| 4 | `Phone` value type | `extraction` | Holds the digits privately, `toString()` is masked, offers `last4()` and a keyed hash. Replaces the `phoneFragment` string in `ParsedOrder`. |
| 5 | Redaction | `extraction` | `ParsedOrder.toString()` prints no text, name or phone. `ExtractionResult.Failure` carries an **enum code**, not a free string. |
| 6 | Order decision logic (pure) | `ingestion` | Status rules and total computation with `Math.*Exact`. No database. |
| 7 | V014 migration | `web` | `catalog_item` table (row-level security, application role SELECT only); `orders.total_minor` nullable with a CHECK tying it to the status; format and length CHECKs on `external_id`, `raw_text` and `label`; INSERT grants for the application role on `orders`, `order_line` and `customer` (no UPDATE, no DELETE). |
| 8 | `OrderRepository`, `CatalogRepository`, `CustomerRepository` | `web` | Prepared statements only, every call inside `TenantScope`, tenant taken from the caller's principal. Registered in `IsolationRegistry` with cross-tenant tests. |
| 9 | `OrderIngestionService` | `web` | The single chokepoint: validate, rate-limit by tenant, normalize, parse, validate, decide, store, audit. Idempotent by `(tenant, externalId)`. |
| 10 | Tests | all modules | Unit, property-based (jqwik), hostile-input corpus, integration against PostgreSQL, log canary, render escaping, isolation, mutation checks. The unit phase must stay green with Docker stopped. |

### Input formats the parser will understand (synthetic examples only)

```
Format A (labelled lines)            Format B (one line)
Order: A-1042                        2 x tomato box, 1 x rice 5kg, total 6000 XAF
Name: Ana K.                         from Ana K., call ending 1234, deliver after 5pm
Phone: +000 000 000 1234
2 x tomato box @ 1500
1 x rice 5kg = 3000
Total: 6000 XAF
Deliver: gate near the market
```

Amounts accept thousands separators (`6 000`, `6,000`, `6.000`) for currencies with scale 0. For a currency with decimals, an ambiguous amount is a parse failure, not a guess. Messages in any other shape become `UNPARSED`: kept and visible, never dropped (requirement 1.5). A real free-text reader needs the optional AI extractor (task 10); I will not fake it with a loose pattern.

### Outcomes of the ingestion service

- `CREATED` (status `PENDING_PAYMENT`)
- `CREATED_NEEDS_REVIEW`
- `CREATED_UNPARSED`
- `DUPLICATE` (same id again; returns the existing order)
- `DUPLICATE_DIFFERENT_TEXT` (same id, different text; **the first order is kept, nothing is overwritten**, and the caller is told)
- `RATE_LIMITED`
- `REJECTED_INPUT` (invalid encoding, control characters, too large, bad id; **not stored**)

## What I will not build

- Payment parsing (task 5), matching (task 6), suspicion rules (task 7), the board and the paste form (task 8), simulators (task 9), the AI extractor (task 10), the signed machine API and HMAC (task 11).
- **No HTTP endpoint in this task.** The service is the entry point. The paste form comes in task 8 and the signed API in task 11. Both will call the same service, so the defenses below apply to both.
- **No ledger posting.** The design's "order accepted: debit receivable, credit revenue" goes with the matching flows in task 6.2. Orders created here carry no ledger entry yet.
- No fuzzy catalog matching, no currency conversion, no phone-number country validation, no catalog management screen, no CSV export, no personal-data deletion.

## The nine decisions, each with my recommendation

| # | Decision | Recommendation | Reason |
|---|---|---|---|
| 1 | **Formats.** Are the two formats (labelled lines, one-line list) enough for this task, with everything else `UNPARSED`? | **Yes.** | A loose pattern for free text would parse wrongly and money would depend on it. Unparsed messages are kept and shown, and the AI extractor (task 10) is the planned answer for the rest. |
| 2 | **Entry point.** No HTTP endpoint in task 4: service only; paste form in task 8, signed API in task 11. | **Yes, no endpoint.** | An endpoint now would need its own authentication, CSRF or HMAC design, which belongs to tasks 8 and 11. The service re-checks everything, so the later entry points inherit the defenses. |
| 3 | **Ledger posting.** No ledger entry for a new order until task 6.2. | **Yes, defer.** | Posting rules are tied to match states and reversals. Doing half of them here risks postings that task 6 then has to untangle in an append-only ledger. |
| 4 | **Phone hashing.** HMAC-SHA-256 with a per-tenant key derived from `app.secret`, instead of Argon2 or a salt stored in the database. | **HMAC with a derived key.** | A phone number has about 10^9 possibilities, so any hash of it can be brute-forced by someone who holds the database and the key. Keeping the key out of the database is the stronger option, and it costs no 19 MiB per order. I will fix the V003 comment. |
| 5 | **Customers.** Find-or-create by phone hash; no de-duplication when there is no phone. | **Yes.** | Without a phone there is no safe key. Duplicate name-only customers are a data-quality problem, not a security one. |
| 6 | **Catalog.** New `catalog_item` table, read-only for the application role, seeded by the operator or by tests for now. | **Yes.** | Least privilege. A seller-managed catalog needs a screen and its own write grant, which is a later decision. |
| 7 | **Schema.** Make `orders.total_minor` nullable for `UNPARSED` and `NEEDS_REVIEW`, with a CHECK, instead of storing 0. | **Nullable with a CHECK.** | A stored 0 looks like a real zero-value order to later code (matching, summaries). A NULL cannot be added up by mistake, and the CHECK forbids NULL on any other status. |
| 8 | **Refused versus stored.** Input with invalid encoding, NUL or control characters, or over the size limit is refused and not stored. | **Refuse and do not store.** | Such text cannot be stored safely (PostgreSQL text cannot hold NUL), and storing it would carry the problem into every later reader. This is my reading of requirement 1.5, named as an interpretation. |
| 9 | **API change.** `ExtractionResult.Failure` gets an enum code instead of a free string, and `ParsedOrder` holds a `Phone` object instead of a string fragment. | **Yes.** | These are scaffold types with one smoke test. A free-string reason lets input text travel into logs; a string phone fragment invites logging. Both are cheap to fix now and costly later. |

If the owner says "go" without changes, I take my recommendation on all nine.

## Every place untrusted text goes (14)

Untrusted text is: the message body, the `externalId`, and anything the parser pulls out of them.

| # | Where it arrives | What could go wrong | Defense (built or tested) |
|---|---|---|---|
| 1 | **Service entry** (from the paste form or the signed API later) | Oversize, bad UTF-8, NUL and control characters, huge ids, flooding | The edge already limits size and encoding (task 3). The service **re-checks everything itself** and does not trust its caller: at most 4,000 characters, valid text, no control characters except line breaks and tab, and `externalId` must match `[A-Za-z0-9._:-]{1,128}`. Refused, never repaired. A per-tenant rate limit (`RateLimits.ingestion`) is checked here, so every future entry point is covered. |
| 2 | **Normalizer** | Look-alike digits and letters; right-to-left overrides that make `1500` read as `0051`; zero-width characters hiding text | Works on a derived copy; the raw text is kept verbatim. Bidirectional-control and zero-width characters are stripped from the copy and non-ASCII digits are mapped to ASCII. The amount cross-check runs against this same normalized copy, so the number the parser used is the number that is checked. |
| 3 | **Parser** | Regular-expression denial of service and CPU burn; integer overflow; unbounded lists; format tricks | Linear scanner; no pattern built from input; no `String.format` with input as the format; no reflection, no deserialization, no script or expression evaluation. Hard caps: 50 items, 80 input lines, quantity 1 to 9,999, amount at most 10^12 minor units, label 100 characters, name 100, note 300. Digits are parsed by hand with a digit cap; all arithmetic uses `Math.*Exact`. A pathological-input corpus plus jqwik fuzzing with a time bound. |
| 4 | **Validator** (last gate) | A parser bug returning nonsense; a total inconsistent with the lines; an unknown currency | The currency must equal the tenant's. Every money amount in the result must appear in the normalized source text. If both a stated total and lines exist and they disagree, the order becomes `NEEDS_REVIEW`; **the stated number is never silently replaced**. A failure here means `UNPARSED`, not a guess. |
| 5 | **Catalog lookup** (requirement 1.2) | `LIKE` wildcards (`%`, `_`) used as injection; one item matching two entries; the wrong price chosen | Exact equality on a normalized key, in a parameterised query inside `TenantScope`; never `LIKE`. The key is unique per tenant. Any unknown or ambiguous item makes the order `NEEDS_REVIEW`. |
| 6 | **Database writes** (`raw_text`, `external_id`, labels, names, note) | SQL injection; second-order injection; NUL bytes; cross-tenant write | Prepared statements only (the existing build scan enforces literal SQL). Values are bound as parameters, never concatenated, never executed. CHECK constraints on length and on the `external_id` format (V014). Row-level security `WITH CHECK` plus the explicit tenant on every row. NUL cannot be stored and is refused earlier. |
| 7 | **Idempotency** | Two requests with one id creating two orders; a replay overwriting the first; id reuse across tenants | `UNIQUE (tenant_id, external_id)` is the arbiter, used with `INSERT ... ON CONFLICT DO NOTHING`. A concurrency test with parallel threads. A replay with other text keeps the first. The same id in two tenants gives two orders (tested). |
| 8 | **Customer and phone** | The full number leaking into storage, logs or errors; a weak hash of a small number space | The full number lives only in a `Phone` object in memory; `toString()` is masked; it never appears in `ParsedOrder.toString()`, logs or errors. Stored as **HMAC-SHA-256 with a per-tenant key derived from `app.secret`** plus the last 4 digits. Unique per tenant. (Deviation from the V003 comment: see weakened defense 3.) |
| 9 | **Logs** | Names, phones or text reaching a log; injected line breaks | A new `IngestionEvents` class takes only ids, an enum outcome and counts. No parameter can hold text. A test sends canary strings (name, phone, label, note, raw text, a CR/LF payload) and searches the captured DEBUG logs. |
| 10 | **Extraction output used as an error or reason** | The parser puts input text in a failure message that is then logged or shown | `ExtractionResult.Failure` becomes an enum code. Free strings from input cannot travel through it. |
| 11 | **Templates** (display of labels, names, notes) | Stored cross-site scripting | No page shows them yet (task 8). To prove the chain now, a test-only probe page renders a stored hostile order; the test checks that the HTML is escaped, the content security policy is present, and the template lint still passes. The task 8 board must keep using `th:text`. |
| 12 | **Audit trail** | Text in audit rows | `audit_log` gets the action `ORDER_INGESTED` with the order id as subject. Nothing else. |
| 13 | **Exports (later task)** | CSV formula injection from labels and names (`=`, `+`, `-`, `@`) | Not built here. I will add a line to the export task's verify list in `tasks.md` so it cannot be forgotten. |
| 14 | **AI extractor (later task)** | Prompt injection through the message; trusting model output for money | Not built. When it is, its output goes through the same validator (amounts must appear in the source), is schema-validated, and is off by default. The enum failure codes and the validator are what it will plug into. |

## Weakened defenses and trade-offs (13), each named with its compensation

1. **Raw text is kept verbatim in `orders.raw_text`.** Needed for audit and for requirement 1.5, but it holds names and phone numbers. *Compensation:* tenant isolation, never logged, never displayed unescaped, deletion by requirement 9.6 in a later task (the column is `NOT NULL`, so deletion will blank it rather than remove it).
2. **Normalization makes differently written input parse the same.** *Compensation:* it works on a derived copy only, the raw text is kept, and the cross-check uses the same copy.
3. **The phone hash is HMAC with a key derived from `app.secret`, not Argon2 and not a salt stored in the database.** If `app.secret` leaks together with the database, numbers can be recovered by brute force, and rotating the secret changes every hash and needs a rehash job (not built). It contradicts the V003 comment and its wording "salt". *Compensation:* the key is never in the database; the full number is never stored; only the last 4 digits are displayed; I will correct the V003 comment.
4. **Name-only customers are not de-duplicated.** Without a phone there is no safe key, so each such order gets its own `customer` row. *Compensation:* none needed for security; it is recorded as a known data-quality limit.
5. **The catalog is read-only for the application role.** It is filled by the operator or by tests until a seller screen exists, so requirement 1.2 can only be exercised with seeded data for now. *Compensation:* this is least privilege, and the write grant will be a separate named decision.
6. **The application role gains INSERT on `orders`, `order_line` and `customer`.** There is still no UPDATE or DELETE and no ownership. *Compensation:* row-level security `WITH CHECK`, a test that UPDATE and DELETE are still refused, and status changes in task 6 will be a separate named grant.
7. **Free text outside the two formats becomes `UNPARSED`.** Safe and honest, but less useful than a model would be. *Compensation:* nothing is dropped; the seller sees it in the board's Unparsed column (task 8); the AI extractor (task 10) is the planned improvement.
8. **Refused input is not stored.** I read requirement 1.5 as covering valid text we cannot understand; text that is not valid text cannot be stored safely. *Compensation:* the caller receives `REJECTED_INPUT` and still holds the text; the interpretation is written down here and in `docs/kiro-notes.md`.
9. **A replay with different text keeps the first order.** Required by requirement 1.3. *Compensation:* the caller is told (`DUPLICATE_DIFFERENT_TEXT`), a fixed-label event is logged, and nothing is overwritten.
10. **A money cap of 10^12 minor units and 50 lines per order.** It refuses some very large legitimate orders (they become `UNPARSED`). *Compensation:* chosen so arithmetic can never overflow; the limits are named constants and can be raised deliberately.
11. **The `externalId` character set is restricted.** An upstream system using other characters would be refused. *Compensation:* widen the set on request, never by dropping validation.
12. **Timing and denial-of-service tests use a wall-clock bound**, and wall-clock tests can be flaky on a slow machine. *Compensation:* a generous bound, plus a deterministic assertion on work done (character steps).
13. **Inherited, unchanged from task 3:** the rate limiter is in memory on a single node, and the tenant setting protects against our own bugs, not against SQL injection. *Compensation:* as already named in the design's trade-off register (items 1 and 5).

## Guards I will mutation-check (break on purpose and show the test fail)

- input length cap; line and item caps; quantity bounds;
- amount cap and overflow guard;
- control-character and bidirectional-character stripping;
- "every amount appears in the source";
- lines against total consistency; currency check;
- `externalId` character set;
- idempotency (remove `ON CONFLICT` and the unique key);
- tenant scoping in the repositories;
- catalog exact match (swap to `LIKE`);
- `toString` redaction of `Phone` and `ParsedOrder`;
- the failure enum (reintroduce a free string);
- log canary;
- render escaping;
- the per-tenant rate-limit call;
- the V014 CHECK constraints.

## Tests I will write (summary)

- **Unit:** normalizer, parser (both formats, separators, scale, each rejection), validator, decision logic, redaction.
- **Property-based (jqwik):** the parser never throws and never exceeds the caps; any accepted amount appears in the source; normalization is idempotent.
- **Hostile corpus:** bidirectional override, zero-width characters, look-alike digits, `%` and `_` in labels, `<script>`, SQL fragments, formula cells, CR/LF, very large input, repeated separators, overflow numbers.
- **Integration (PostgreSQL):** idempotency under 16 parallel submissions; cross-tenant isolation (same `externalId` in two tenants, tenant A cannot read tenant B's orders or catalog); constraint checks; grants (INSERT yes, UPDATE and DELETE no); log canary; render escaping.
- The unit phase must stay green with Docker stopped.
