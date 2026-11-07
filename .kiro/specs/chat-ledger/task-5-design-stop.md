# Task 5 design stop: payment extraction

Status: **implemented** (task 5 done; see `docs/kiro-notes.md`, "Task 5"). Approved by the owner on all twelve decisions, with D1 changed and four additions (below). The text below is the proposal as approved; where it speaks of RE2/J, a pattern whitelist, a compile deadline or weakened defense 3, the owner's change D1 = C replaced it. The register rows that were built are items 32 to 50 in `design.md`.

## Owner's changes (override anything below that disagrees)

1. **D1 = option C. No regular-expression engine and no new dependency (no RE2/J).** A template is *literal text with typed holes*, matched by our own linear scanner over the whole normalised message. Each hole has a type with its own character set and maximum length: `amount`, `currency`, `senderName`, `senderPhone` (or `senderLast4`), `transactionId`, `paidAt`, `reference`. The scan is deterministic: consume allowed characters up to the maximum, then require the next literal text; no backtracking, no search, no two holes without a literal between them. Kept: template at most 500 characters, at most 10 holes, at least 12 literal characters, strict JSON binding, field rules, examples and counter-examples, and the ambiguity rule. **Dropped:** the pattern whitelist parser, the compile deadline thread, the RE2/J-specific gates and weakened defense 3. **New named cost:** templates are less flexible than patterns (no optional parts, no alternation inside one template, delimiter rule).
2. **Optional `reference` hole.** The order code the payer wrote in the transfer note. Stored in `payment.reference` with the same format CHECK as `orders.order_ref`, and compared with the same key function (`ReferenceKeys`). Without it the maximum score is 75 and the auto-match threshold is 85, so nothing could ever auto-match. A provider text that sometimes has a reference and sometimes not is handled by **two templates** (with and without the reference), kept apart by their literal text, with the ambiguity rule as the safety net. At least one example with and one without ships.
3. **`tasks.md`, task 6:** an `UNVERIFIED` payment is never auto-matched (suggested at most); a `DUPLICATE` payment is never posted to the ledger; matching uses `payment.reference`.
4. **`tasks.md`, task 9:** the simulators generate timestamps inside the plausibility window relative to the current time, so demo payments are not `UNPARSED`.

Consequences found while implementing: the shipped set is five templates (ProviderA with and without a reference, ProviderB with and without a reference, ProviderA forwarded); hole character sets are narrower than the database CHECKs (a transaction id or reference hole reads letters, digits, `_` and `-`; so a `.` or `:` in a reference cannot be read from an SMS); time zones are fixed offsets only (no named zones, no daylight-saving); the amount/phone/name holes may contain spaces, so a trailing space run is given back to the literal that follows.

Sources re-read: requirements 2, 5 (the flags that depend on payment fields), 8 and 9; the design (extraction pipeline, provider templates, data model, API, tenancy); tasks 5.1 to 5.3 and the payer-lookup line; migrations V004, V010, V011; the task 4 code (`extraction`, `ingestion`, `orders`) and `task-4-design-stop.md`.

## Facts checked (repository and library)

- `ParsedPayment` is a scaffold record (provider, transaction id, amount, currency, sender name, sender last 4 as a string, timestamp, raw text). There is no template engine, no payment service and no payment repository.
- The `payment` table (V004) has `provider`, `amount_minor` and `currency` as `NOT NULL`, a nullable `tx_id`, and `UNIQUE (tenant_id, provider, tx_id)`. The application role has **SELECT only** on it (V010); row-level security is on (V011). Four consequences:
  1. Requirement 2.4 says the *new record* is marked `DUPLICATE`, but the unique constraint refuses a second row with the same transaction id. A duplicate cannot be stored today.
  2. An `UNPARSED` payment has no provider and no amount, so those columns cannot stay `NOT NULL` for that status.
  3. There is no `external_id`, so there is no idempotency key like the one orders have.
  4. There is nowhere to keep a sender's phone hash, which the payer lookup in tasks 5 and 6 needs.
- Requirement 2.2 asks for configurable templates; the design says templates are data (name, regular expression with named groups, field mapping, date format) and "can be added without a release".
- Java's `java.util.regex` is a backtracking engine: a template plus a hostile text can take exponential time. **RE2/J 1.8** (`com.google.re2j:re2j`) matches in time linear in the input. It is the latest release (2025-01-09; the one before is from 2022-06-30). OSV.dev (queried with the public package name and version only) has **no advisory** for 1.8. But the project has **two open issues, #168 and #215, reporting that compiling certain case-insensitive patterns can hang** (`Pattern#compile` loops). So a linear-time matcher does not by itself make a hostile *template* safe: the compiler is also an attack surface. A web search also returned an advisory for `re2js`, a JavaScript port; it is a different project and does not apply.
- Reusable from task 4: `TextNormalizer`, `AmountParser` and `SourceAmounts` (same package), `Phone` and `PhoneNumbers.canonicalDigits`, `TextKeys`, `InputChecks`, `RateLimits`, `PhoneKeys`, `TenantScope`, `AuditLogRepository.appendWithin`, `HostileCorpus`, the log-capture and fixture patterns.

## What I will build

| # | Piece | Where | Notes |
|---|---|---|---|
| 1 | `PaymentTemplate` (record) and a strict JSON loader | `web` | Templates are JSON files shipped in the jar (`payment-templates/*.json`): id, provider code, currency, whether a transaction id is required, time zone, date format, pattern, examples, counter-examples. Unknown fields, duplicate keys and oversize files are refused. |
| 2 | `TemplateValidator` | `extraction` | A whitelist parser of the pattern text (grammar below) plus caps. A pattern that uses anything outside the whitelist is refused before it reaches the regex compiler. |
| 3 | `TemplateEngine` | `extraction` | Compiles with RE2/J, matches the **whole** normalised message (never a search), returns the captured groups. Refuses to start the template if compilation does not finish within a deadline. |
| 4 | Field parsers | `extraction` | Typed parsers for the captured groups: amount (reuses `AmountParser`), transaction id (charset and length), sender name, sender phone (reuses `PhoneNumbers.canonicalDigits` and `Phone`), timestamp (strict, fixed-width, numeric). A captured value is never used raw. |
| 5 | `PaymentTextParser` and `PaymentValidator` | `extraction` | Normalise (`TextNormalizer`), match every active template, require exactly one distinct result, parse the fields, then the last gate: amount written in the message, currency equals the tenant's, timestamp plausible, bounds. Failures are `FailureReason` codes. |
| 6 | `ParsedPayment` revised | `extraction` | Holds a `Phone` (masked when printed) instead of a bare last-4 string, plus the template id; `toString` prints no text, name or number. |
| 7 | `PaymentDecider` and `PaymentPipeline` (pure) | `ingestion` | Status from the parse: `RECEIVED`, `UNVERIFIED` (a template that declares no transaction id), `UNPARSED`. No database. |
| 8 | V015 migration | `web` | `payment`: `external_id` (unique per tenant, format CHECK); `provider` and `amount_minor` nullable but only for `UNPARSED` (CHECK ties them to the status, like `orders.total_minor`); `currency` stays `NOT NULL`; `sender_phone_hash` and `duplicate_of`; the transaction-id uniqueness becomes a **partial unique index that excludes `DUPLICATE` rows**; format and length CHECKs on every column that receives SMS text; `INSERT` (no UPDATE, no DELETE) for the application role. |
| 9 | `PaymentRepository` | `web` | Prepared statements only, every call in `TenantScope`, one transaction for the payment, the duplicate link and the audit row. Registered in `IsolationRegistry` with a cross-tenant contract test. |
| 10 | `PaymentIngestionService` and `PaymentEvents` | `web` | The single chokepoint, same shape as `OrderIngestionService`: re-check input, rate-limit by tenant, idempotency, templates, duplicate detection, store. The log takes ids and codes only. |
| 11 | Three synthetic templates with examples | `web` | ProviderA (English, with a transaction id), ProviderB (French, `FCFA`, with a transaction id), and a ProviderA "forwarded" shape with no transaction id (gives `UNVERIFIED`). No real operator wording. |
| 12 | Tests | all modules | Unit, property-based, hostile corpus, hostile templates, integration against PostgreSQL, log canary, render escaping, isolation, mutation checks. The unit phase stays green with Docker stopped. |

### Outcomes of the payment service

`CREATED` (status `RECEIVED`), `CREATED_UNVERIFIED`, `CREATED_DUPLICATE_TX` (status `DUPLICATE`, linked to the first payment), `CREATED_UNPARSED`, `REPLAY` (same external id, same text: returns the existing payment), `REPLAY_DIFFERENT_TEXT` (same external id, other text: first payment kept, caller told), `RATE_LIMITED`, `REJECTED_INPUT` (not stored).

## What I will not build

- Matching, scoring, ledger postings and the auto/suggest flows (task 6); the suspicion rules and their flags (task 7). Task 5 only sets the status that those tasks read.
- **No ledger posting of any kind.** A `DUPLICATE` payment must never get a posting (requirement 2.4); I add a line to task 6 in `tasks.md` so it cannot be forgotten.
- No HTTP endpoint, no paste form, no signed machine API (tasks 8 and 11); the service is the entry point, as in task 4.
- No runtime way to add a template: no upload screen, no database table of templates, no operator command (see decision D2).
- No AI extractor (task 10), no OCR or screenshot reading, no automatic creation of customers from an SMS sender, no phone-number country logic, no month names or other locale-dependent dates, no real operator formats.

## How a template is added without opening a regular-expression denial of service

**What a template is.** A small JSON file in the repository (synthetic example, shape only):

```json
{
  "id": "provider-a-received",
  "provider": "PROVIDER_A",
  "currency": "XAF",
  "transactionIdRequired": true,
  "zone": "+01:00",
  "dateFormat": "dd/MM/yyyy HH:mm",
  "pattern": "^You received (?P<amount>[0-9 ,.]{1,15}) (?P<currency>[A-Za-z ]{3,5}) from (?P<senderName>[A-Za-z .'-]{2,40}) \\((?P<senderPhone>[0-9+ ]{7,20})\\)\\. Txn (?P<transactionId>[A-Z0-9]{6,20})\\. (?P<paidAt>[0-9/: ]{16})$",
  "examples": [ { "text": "...", "expect": { "amount": 5000, "transactionId": "..." } } ],
  "counterExamples": [ "..." ]
}
```

**Where it lives and who can add one.** In `web/src/main/resources/payment-templates/` inside the jar. Adding a template is a pull request, reviewed and built by CI. Nothing from outside the build ever reaches the regular-expression compiler at runtime. (The design's "without a release" is deferred, with a clean interface for a later source; see D2.)

**The gates a template passes, in order, at build time (tests) and again at every startup:**

1. **Strict file binding.** At most 8 KiB; unknown fields, duplicate keys and wrong types are refused.
2. **Field rules.** `id` and `provider` match a fixed character set; `currency` is one the tenant model knows; `zone` is a fixed offset or an IANA name from an allow-list; `dateFormat` uses only the letters `y M d H m s` and the separators `- / : . space` (numeric, fixed width, no locale).
3. **Whitelist parse of the pattern.** The pattern text is parsed by our own small tokenizer against the grammar below. Anything not in the grammar is refused *before* the regex compiler sees it. This is what keeps the compiler away from the constructs behind the open RE2/J hang issues.
4. **Caps.** Pattern at most 500 characters; at most 10 named groups; nesting depth at most 3; at most 8 alternatives; every repetition bound at most 64; at least 12 literal characters (so a template cannot be "match anything").
5. **Group and field mapping.** Every field a template needs (`amount`, `paidAt`, `transactionId` when required) has exactly one named group; every group maps to exactly one known field; no unknown group names.
6. **Compile with RE2/J on a daemon thread with a 5-second deadline.** A compile that does not finish makes the application refuse to start (see the table below); the template cannot be used.
7. **Examples and counter-examples.** Every example must match and produce exactly the expected fields. Every counter-example must not match. The counter-examples include the examples of every *other* template, every order sample from task 4, and the hostile corpus.
8. **Linear-scaling check.** Matching a hostile text of length n and of length 8n must not differ by more than a generous factor.
9. **Ambiguity check.** No example of one template may be matched by another template.

**Pattern grammar (the whitelist).** Anything else is refused.

| Allowed | Notes |
|---|---|
| Exactly one `^` at the very start and one `$` at the very end | The whole message must match. Text pasted before or after a real confirmation (a forgery wrapper) does not match. |
| Literal characters, including accented letters | Matching is case-sensitive; provider SMS are machine-written with fixed wording. |
| Escaped metacharacters `\.` `\(` `\)` `\[` `\]` `\+` `\*` `\?` `\|` `\\` `\-` `\/` | |
| `\d` and `\s` | ASCII only in RE2. |
| Bracket classes with **ASCII** ranges and literals, optionally negated | No class from a Unicode property, no POSIX class. |
| Named capture `(?P<name>...)` and non-capturing `(?:...)` | |
| Alternation `\|` | At most 8 alternatives. |
| Repetition `?` and `{m}` and `{m,n}` only | **No `*`, no `+`, no `{m,}`: every repetition is bounded** (bound at most 64). A capture group may contain only bracket classes with bounded repetition. |
| **Refused** | Flags of any kind (`(?i)`, `(?s)`, `(?m)`, `(?U)`: this is the area of the open hang issues), `\p` and `\P`, `\x{...}`, `\Q...\E`, `\b`, lookaround, backreferences, `.` outside a class, lazy quantifiers, any unbounded repetition. |

**The engine.** RE2/J matches in time linear in (input length x program size). The input is at most 1,000 characters after normalisation and at most 20 templates are active, so the work per message is bounded. The match is `matches()` over the whole message.

**What happens to what a template captures.** Never used raw. The amount goes through `AmountParser` (strict, integer minor units); the transaction id must match a charset and length; the sender phone goes through `PhoneNumbers.canonicalDigits`; the name is length-bounded and free of control characters; the timestamp is parsed with a strict resolver and checked for plausibility. Then the validator confirms the amount is written in the message and the currency is the tenant's.

### When a template is hostile, wrong or slow

| Case | What happens |
|---|---|
| File is malformed, has unknown or duplicate fields, or is too big | Refused at load. The build fails (test over every shipped file). At startup the application **refuses to start** and names the template id and a reason code, never the pattern text. |
| Pattern uses anything outside the grammar, or breaks a cap | Refused by the whitelist parser, before the regex compiler. Same build and startup result. |
| Pattern is a catastrophic-backtracking shape (`(a+)+`, `(a\|a)*`) | Cannot be written: no unbounded repetition exists in the grammar, and the engine is linear anyway. |
| Compilation hangs (the open RE2/J issue class) | The compile runs on a daemon thread with a 5-second deadline. If it is exceeded, startup fails; the stuck thread dies with the process. Shipped templates are the same files CI compiled, so this means a build defect, caught by the build test (which has a timeout). |
| Template is too broad (matches things it should not) | Counter-example tests over the other templates, the order samples and the hostile corpus fail the build; at runtime, a message matched by more than one template with different results is **refused as `UNPARSED`** (reason `AMBIGUOUS_TEMPLATE`), never resolved by order. |
| Template is correct but a message makes matching slow | Linear time and the input cap bound it. The engine cannot be pre-empted, so the service measures the total match time per message and logs a fixed-label event with the template id when it exceeds 250 ms. It does **not** disable the template automatically: an attacker could otherwise switch a provider off by sending slow text. |
| Group maps to the wrong field | Refused at load (mapping rules); the typed parser and the "amount is in the message" check catch the rest. |
| Template or example text accidentally contains a real name, number or operator wording | A test scans shipped templates for digits runs that look like phone numbers and for non-synthetic provider names (`PROVIDER_A`, `PROVIDER_B` only). |

## Every place untrusted SMS text goes (19)

Untrusted text is: the SMS body, the `externalId`, and anything captured from them. Template files are treated as untrusted until they pass the gates above.

| # | Where it arrives | What could go wrong | Defense (built or tested) |
|---|---|---|---|
| 1 | **Service entry** (paste form task 8, signed API task 11) | Oversize, bad UTF-8, NUL and control characters, huge ids, flooding | The service re-checks everything itself (`InputChecks`, with an SMS cap of 1,000 characters): refused, never repaired. The per-tenant ingestion rate limit is checked here, so every future entry point is covered. |
| 2 | **Normaliser** | Look-alike digits, right-to-left overrides, zero-width characters | `TextNormalizer` (task 4) on a derived copy; the raw text is kept verbatim. Templates are written against the normalised form, and the examples are tested in that form. |
| 3 | **Template matching** (the new regular-expression surface) | Denial of service by hostile text; embedding a real-looking line in a longer message | Linear-time engine, 1,000-character cap, at most 20 templates, whole-message match anchored at both ends, template grammar whitelist. |
| 4 | **Captured groups** | A group holding more than the field should; a group mapped to the wrong field | Groups are bounded classes (grammar), mapping is validated at load, and each value goes through a typed parser; nothing is used raw. |
| 5 | **Amount** | Overflow, ambiguity, a number not in the message | `AmountParser` (strict, 13 digits, cap 10^12), then "written in the message" on the normalised text, as for orders. |
| 6 | **Timestamp** | Hostile date strings; locale tricks; far-future or ancient dates | Fixed-width numeric pattern from an allow-list, strict `DateTimeFormatter` with root locale, per-template zone, plausibility window; outside the window the message is `UNPARSED`. |
| 7 | **Transaction id** | Injection, huge or odd values; used in lookups | Charset `A-Z a-z 0-9 . _ : -`, 6 to 40 characters; equality lookup only, bound parameter; CHECK in the database. |
| 8 | **Sender name** | Markup, formulas, log forging; identity spoofing | Length-bounded, no control characters, stored as data, escaped on output (tested end to end), CSV-neutralised on export (existing line in `tasks.md`), never used as a key. |
| 9 | **Sender phone** | Leaking the number; two spellings making two people | `PhoneNumbers.canonicalDigits` and `Phone` (masked when printed), hashed with `PhoneKeys` under the tenant's key; only the hash and last four digits are stored. The same function and key as orders, so a payment's hash equals the customer's hash for the same number (tested across both services). |
| 10 | **Duplicate detection** | A second submission of the same transaction; a race between two requests | Equality lookup on (tenant, provider, transaction id); the partial unique index is the arbiter under concurrency; the loser is stored as `DUPLICATE` linked to the first; tested with parallel threads. |
| 11 | **Database writes** (raw text, ids, name, hash) | SQL injection; NUL; cross-tenant write | Prepared statements only (the existing build scan), bound parameters, CHECK constraints on length and format (V015), row-level security `WITH CHECK`, explicit tenant on every row. |
| 12 | **Idempotency** | Two requests creating two payments; replay overwriting | `UNIQUE (tenant_id, external_id)` with `ON CONFLICT DO NOTHING`; a replay with other text keeps the first and says so. |
| 13 | **Logs** | Names, numbers, ids or text reaching a log; forged log lines | `PaymentEvents` takes ids, enums and counts, plus the template id (shipped data). No parameter can hold SMS text. A canary test searches captured DEBUG logs. The regex library's own error messages (they quote the pattern) are never logged: only the template id and a reason code. |
| 14 | **Failure reasons** | Input text travelling in an error | `FailureReason` stays an enum; new codes only (for example `AMBIGUOUS_TEMPLATE`, `DATE_IMPLAUSIBLE`, `TEMPLATE_UNAVAILABLE`). |
| 15 | **Template files** | A hostile or careless template | The nine gates above, at build and at startup; fail closed. |
| 16 | **Pages** (sender name, transaction id, raw text on the board) | Stored cross-site scripting | No page shows them until task 8. A test-only probe page renders stored hostile payments through `th:text`; the HTML is checked for escaping, the policy header and the template lint, as for orders. |
| 17 | **Audit trail** | Text in audit rows | `audit_log` gets `PAYMENT_INGESTED` with the payment id as subject, nothing else. |
| 18 | **Exports (later)** | CSV formula injection from names and ids | Not built here; the existing line in `tasks.md` already covers it, and I add payment fields to it. |
| 19 | **Cross-channel text** (an order pasted as a payment and the reverse) | A message treated as the wrong kind | An order text matches no payment template and becomes `UNPARSED`; a payment SMS fed to the order parser becomes `UNPARSED`. Both are tested. |

## Weakened defenses and trade-offs (21), each named with its compensation

1. **Authenticity of an SMS cannot be verified from its text.** A forged text in the exact provider shape looks real. *Compensation:* the system never moves money; `UNVERIFIED` for a text with no transaction id; `DUPLICATE` for a repeated id; the task 7 flags (near-miss amount, name mismatch, time anomaly); the seller has the last word; matching (task 6) must not auto-confirm on a payment alone; the machine feed will need the HMAC signature (task 11).
2. **Raw text is kept verbatim in `payment.raw_text`** and holds names and numbers. *Compensation:* tenant isolation, never logged, never printed by a value type, escaped on output (tested), deletion by requirement 9.6 in a later task (the column is `NOT NULL`, so deletion blanks it).
3. **A new third-party library, RE2/J, runs on untrusted text and untrusted-until-validated patterns.** Two open issues report compile hangs; the last release is from January 2025. *Compensation:* the version is pinned and scanned by the dependency check in CI; the grammar forbids the flags and Unicode classes behind the known hang class; templates come only from the build; compile has a deadline; a template that does not compile stops startup; the engine itself matches in linear time.
4. **Templates ship with the release.** The design said they could be added without one. *Compensation:* the loader sits behind an interface so a database source can be added later with the same nine gates; the cost is that a new provider format needs a pull request.
5. **Numeric dates only.** Month names (including French ones) are not supported, so a provider that writes "3 nov." needs a different approach. *Compensation:* the template is refused at load rather than half working; named for the owner.
6. **One fixed time zone per template and no daylight-saving logic.** *Compensation:* zone declared per template and tested; the target region has no daylight-saving time; other regions need a template with the right zone.
7. **Plausibility window for the timestamp** (default: not more than 15 minutes in the future, not more than 35 days in the past). A genuine confirmation pasted late becomes `UNPARSED`. *Compensation:* the message is kept and visible; the window is a named constant.
8. **"The amount is written in the message" proves presence, not meaning** (carried over from orders). *Compensation:* the typed parser, the template's fixed wording around the amount, and the task 7 near-miss rule.
9. **Ambiguity refuses.** A genuine message matched by two templates with different results becomes `UNPARSED` instead of being guessed. *Compensation:* the ambiguity check at build time makes it a development error, not a runtime one.
10. **A slow match cannot be pre-empted and a slow template is not switched off automatically.** *Compensation:* linear engine, input cap, a logged fixed-label event with the template id; automatic switch-off would let an attacker disable a provider.
11. **A sender name is free text from an SMS.** It is never used as a key or identity. *Compensation:* escaped on output, length-bounded, matched against customers only by similarity in task 6/7, advisory.
12. **The canonical phone limit carries over:** a sender number written with and without a country code is two different hashes, so the payer lookup may miss. *Compensation:* named; the same function is used everywhere, so the miss is predictable, and the seller can still confirm by hand.
13. **No customer is created from an SMS sender.** A forged SMS must not be able to add people. *Compensation:* only the hash and last four digits are stored on the payment; the lookup is read-only (task 6).
14. **Duplicates are stored.** A replayed valid SMS under new external ids adds `DUPLICATE` rows. *Compensation:* the per-tenant rate limit bounds the growth; each row is at most 1,000 characters; the rows are the evidence for the duplicate flag.
15. **The transaction-id uniqueness is now a partial index** (`DUPLICATE` rows excluded) instead of a plain constraint. Whether a row is a duplicate depends on application logic. *Compensation:* the index still makes two non-duplicate rows with the same id impossible, tested under concurrency; a CHECK ties `DUPLICATE` to a non-null `duplicate_of`.
16. **The application role gains `INSERT` on `payment`** (still no UPDATE or DELETE). *Compensation:* row-level security `WITH CHECK`, a test that UPDATE and DELETE are refused; status changes are a separate named grant in task 6.
17. **New CHECKs on the existing `payment` table are `NOT VALID`.** *Compensation:* they apply to every new and changed row; `VALIDATE CONSTRAINT` once old data is known to fit.
18. **Hard caps:** 1,000 characters per SMS, 20 active templates, 500-character patterns, 10 groups, repetition bound 64. Larger input is refused or becomes `UNPARSED`. *Compensation:* named constants, raised only deliberately.
19. **Free-typed text and screenshots stay `UNPARSED`.** Requirement 2.5 mentions screenshots, but there is no OCR. *Compensation:* the text is kept; `UNVERIFIED` is reached only by a template that declares "no transaction id".
20. **Timing and linear-scaling tests use a wall-clock ratio**, which can be flaky on a slow machine. *Compensation:* generous factor, plus the deterministic structural facts (no unbounded repetition can be written, flags are refused).
21. **Inherited:** the PostgreSQL server log can contain the failing row of a constraint violation (register item 31); the in-memory rate limiter; the tenant setting protects against our own bugs, not against SQL injection.

## Decisions I need from you, each with my recommendation

| # | Decision | Recommendation | Reason |
|---|---|---|---|
| D1 | **Regular-expression engine.** A: RE2/J with the pattern whitelist. B: `java.util.regex` with a restricted pattern subset and a step budget on the input. C: no regular expression at all, a template is literal text with typed holes (`{amount}`, `{transactionId}`) matched by our own linear scanner. | **A.** Fall back to **C** if you do not want a new dependency. Not B. | A is linear by construction, keeps the "regex with named groups" you wrote in the design, and the whitelist avoids the known compile-hang class. C is the safest (no engine at all) but changes the design wording and is less flexible. B depends on proving a subset free of backtracking blow-ups, which I cannot do cheaply; a step budget stops it only after the damage starts. |
| D2 | **Where templates live.** (1) JSON files in the jar, added by pull request. (2) A database table plus an operator command, so a template can be added without a release. | **(1) now**, behind a `TemplateSource` interface so (2) can follow. | With (1) no text from outside the build ever reaches the regex compiler at runtime, which matters because of the open compile-hang issues. (2) needs a new write path, a new privilege and a runtime validation and rollback story; it is a separate decision with its own design stop. It does defer "without a release". |
| D3 | **A shipped template that fails a gate at startup.** Refuse to start, or disable only that template. | **Refuse to start.** | A broken template that is silently disabled turns a whole provider's payments into `UNPARSED` unnoticed. CI makes this a build failure first. |
| D4 | **Two templates match one message with different results.** | **`UNPARSED` with `AMBIGUOUS_TEMPLATE`.** | Fail closed; never let template order decide money. |
| D5 | **Sender.** Template may capture a full number (hashed with `PhoneKeys`, last 4 kept) or only the last 4 digits (no hash). No customer is ever created from an SMS. | **Yes.** | Same canonical function and key as orders, so the lookup in task 6 is one equality; no forged-SMS path to new customers. |
| D6 | **Schema V015.** `external_id` with unique and format CHECK; `provider` and `amount_minor` nullable only for `UNPARSED` (CHECK); `currency` stays `NOT NULL` and is the tenant's; `sender_phone_hash` with format and pair CHECKs; `duplicate_of`; partial unique index on (tenant, provider, transaction id) excluding `DUPLICATE`; `INSERT` grant. | **Yes.** | Each change is forced by requirement 2.3 to 2.5 and by task 4's conventions; none widens anything beyond INSERT. |
| D7 | **Duplicates.** Store the new record as `DUPLICATE`, linked to the first by `duplicate_of`, and never post it to the ledger. Add a line to task 6 in `tasks.md`. | **Yes.** | Required by requirement 2.4; the stored duplicate is the evidence for the task 7 flag. |
| D8 | **`UNVERIFIED`.** Reached only through a template that declares no transaction id (the "forwarded" shape). Free-typed text and screenshots stay `UNPARSED`. | **Yes.** | A text with no template is not a payment claim we can read; pretending otherwise would be guessing. |
| D9 | **Timestamps.** Numeric fixed-width formats, a zone per template, plausibility window of 15 minutes ahead and 35 days back, outside it `UNPARSED`. Are those two numbers right for you? | **Yes, with these numbers.** | They are conservative and named constants; tell me if you want a longer past window. |
| D10 | **Rate limit.** Orders and payments share one per-tenant ingestion budget (60 a minute, burst 30). | **Shared.** | One tenant's total intake is what the limit protects; two budgets would double it. |
| D11 | **Entry point and size.** Service only, no HTTP endpoint (as in task 4); SMS cap of 1,000 characters (a concatenated SMS is at most about 1,500 characters; 1,000 covers the usual confirmation). | **Yes.** | The paste form and the signed API call the same service later. |
| D12 | **Shipped templates.** ProviderA (English), ProviderB (French, `FCFA`), and ProviderA "forwarded" (no transaction id), all synthetic, each with at least three examples and five counter-examples. | **Yes.** | Meets "at least two synthetic formats" and exercises `UNVERIFIED`. |

If you say "go" without changes, I take my recommendation on all twelve.

## Guards I will mutation-check (break on purpose and show the test fail)

- the pattern whitelist: each refused construct (flags, `\p`, `\b`, unbounded repetition, a Unicode class, a missing anchor, an oversize pattern, too many groups, an over-large bound, too few literals);
- the compile deadline; the startup-fails-closed behaviour; strict file binding (unknown and duplicate fields);
- whole-message matching (change to a search); both anchors;
- the ambiguity rule; the group-to-field mapping rule;
- the typed parsers: amount, transaction id charset and length, name, phone, timestamp format;
- the plausibility window (both ends); the zone;
- "the amount is written in the message" for payments (including a parser that lies); the currency check; bounds;
- the service's input check and SMS cap; the rate limit; idempotency (`ON CONFLICT` removed) and the replay-with-other-text outcome;
- duplicate detection (equality lookup removed; the partial index changed to a plain one); the concurrent-duplicate test;
- the `UNVERIFIED` rule; the sender hash equals the customer hash for the same number; per-tenant key;
- `toString` redaction of `ParsedPayment`; the failure enum; the log canary; render escaping;
- the V015 CHECK constraints and grants (INSERT yes, UPDATE and DELETE no), row-level security on the changed table;
- a scan that no shipped template contains a phone-like digit run or a non-synthetic provider name;
- the structural scan from task 4 extended to the payment code (no pattern, format string, reflection or process built from text), with the regex library confined to one class.

## Tests I will write (summary)

- **Unit (no Docker):** whitelist parser (every allowed form and every refusal); engine (match, no match, ambiguity, group mapping); each field parser; payment validator including the lying parser; decider and pipeline; loader (strict binding); every shipped template against its examples, counter-examples, the other templates, the order samples and the hostile corpus; linear-scaling and slow-input checks; redaction; structural scan.
- **Property-based (jqwik):** the engine never throws and never exceeds the caps on random text; any accepted amount is written in the message; normalisation stays stable.
- **Hostile templates:** a corpus of malformed and dangerous templates (nested quantifiers, flags, unbounded repeats, a Unicode-class pattern from the open issue, an oversize pattern, a match-everything pattern, duplicate keys) that must all be refused.
- **Integration (PostgreSQL):** idempotency under 16 parallel submissions; concurrent duplicates (one `RECEIVED`, the rest `DUPLICATE`, all linked); cross-tenant isolation; constraints and grants; sender hash equals the customer hash across the order and payment services; log canary; render escaping.
- The unit phase must stay green with Docker stopped; the existing scan that forbids Docker in unit tests keeps covering the new tests.
