# Task 8 design stop: board and summary UI

Status: **approved by the owner on all twenty-two decisions (D1 to D22), with five conditions; implemented in stop 2.** What was built, and where it differs from this text, is in `docs/kiro-notes.md` ("Task 8") and the design register (items 76 to 95). The proposal below is kept as written.

Sources re-read: `tasks.md` tasks 6 to 9; requirements 1.4, 3, 4.5, 5, 6, 9, 10; the design (Tenancy and authentication, Logging and audit, register items 1 to 75); `task-6-design-stop.md`; `SecurityConfig`, `Permission`, `Role`, `HomeController`, `TenantUserDetails`, `RateLimits`, `SecurityProperties`, `RequestGuardFilter`; `layout.html`, `home.html`, `login.html`; `TemplateLintTest`, `HtmxContractIT`; the order and payment services and their result types; `OrderRepository`, `PaymentRepository`, `MatchRepository`, `MatchingService`, `ReasonCode`, `FloorCode`, `Reason`; the `flag`, `match`, `orders`, `payment`, `tenant` tables and V016's transition triggers; the `suspicion` module (a skeleton: `FlagCode`, `SuspicionFlag`, no rules).

## Facts checked (and the gaps they reveal)

- **Gap 1: nothing reads the data for a screen.** The repositories can insert, find one row by id and count. No query lists orders or payments by status, none returns a customer's name with an order, none sums a day. The board needs a read model of its own (`BoardRepository`), tenant-scoped like every other repository, with a bounded page size.
- **Gap 2: task 7 (flags) is not built.** The `flag` table exists and is empty; `suspicion` has only enum codes. The board's "Flagged" column can show only what the existing data already says (duplicate and unverified payments) until task 7. Task 8 must read flags so that task 7 needs no UI change.
- **Gap 3: the stored raw text holds full phone numbers.** Requirement 1.4 says phones are shown masked in the UI. The database keeps only a hash and the last four digits for a customer or sender, but `raw_text` is the message as pasted, and a customer writes "Phone: +999 000 000 1234" in it. Showing the raw text would show the number. This is the main leak on a page and is decided in D10.
- **Gap 4: reasons are written but never read.** `Reason.toJson` exists; there is no reader. The page needs a strict one (known codes, integer points) and a sentence for each of the 11 codes.
- **Gap 5: a `NEEDS_REVIEW` order has no way out.** V016's transition trigger forbids any move out of it for everyone, and the application role has no `UPDATE` on `orders.total_minor`. Task 8 must add the named transition (and the `ORDER_ACCEPTED` posting at that moment).
- **Gap 6: a tenant has no time zone.** "Daily" summary needs day boundaries. UTC would split a Cameroon day (UTC+1) in the wrong place.
- **Gap 7: no static files exist and `/assets` would be denied.** `anyRequest().denyAll()` blocks any path not listed. There is no stylesheet and htmx is not vendored (`layout.html` says task 8 adds it). The CSP is `script-src 'self'; style-src 'self'` with no inline code, and a lint bans `style` attributes, inline scripts and event handlers.
- **Gap 8: htmx keeps page snapshots in the browser's `localStorage` by default.** Customer names would sit there after sign-out on a shared phone. History must be switched off in the htmx configuration.
- **Gap 9: no named limits for rematch or export** in `RateLimits`/`SecurityProperties`.
- Reused as they are: `TenantUserDetails` (the tenant always comes from the principal), `@PreAuthorize` on `Permission` authorities, `TenantScope`, `RateLimits`/`InMemoryRateLimiter`, `AuditLogRepository`, `MatchingService` (every seller action, already refusing a non-seller), `Postings`/`ChartOfAccounts`, `TextKeys`, the template lint, the CSP and the `hx-headers` CSRF contract on `<body>`.

## What I will build

| # | Piece | Where | Notes |
|---|---|---|---|
| 1 | `BoardRepository`, `SummaryRepository`, `ExportRepository`, `BooksCheck` | `web` | Read-only, prepared statements, every statement names the tenant, registered in `IsolationRegistry`, owner-run scoping test. Page size bounded; cursor paging. |
| 2 | Controllers: board, paste, detail, seller actions, summary, export, books check | `web` | One `@PreAuthorize` per handler (a test fails if one lacks it). Typed parameters only (UUID, enum, date, bounded int). |
| 3 | Templates, one stylesheet, vendored htmx | `web/src/main/resources` | Server-rendered; works without JavaScript; htmx only swaps fragments. |
| 4 | `Display` (text cleaning, truncation), `MoneyFormat`, `PhoneMask`, `ReasonSentences` | `web` | The only way untrusted text reaches a template. |
| 5 | V018 | `web` | `tenant.timezone`; `UPDATE (total_minor)` on `orders` with a trigger that freezes it except in one transition; the `NEEDS_REVIEW` to payable transition; a commit-time check that such an order has its `ORDER_ACCEPTED` posting; indexes for the board and the summary. |
| 6 | `MatchingService.approveOrder` (or an `OrderApprovalService`) | `web` | The seller sets the total of a `NEEDS_REVIEW` order; one transaction; posts `ORDER_ACCEPTED`; then the matcher looks at the new order. |
| 7 | `Reason.fromJson` | `matching` | Strict reader, unit-tested. |
| 8 | Named limits | `RateLimits`, `SecurityProperties` | `rematch-per-tenant`, `export-per-tenant`, `actions-per-user`, with bounds checked at startup. |
| 9 | Tests, lints, a browser check outside the Maven build | all | See "Tests" and D18. |

## Pages and routes

Everything is under `/app` (already "signed in"), plus public static files under `/assets`. The tenant and the user come from the session principal only, never from a path, query or form field. A UUID in a path that is not this tenant's gives **404** (not 403: no hint that it exists elsewhere). Every POST needs the CSRF token (hidden field from `th:action` for plain forms, `hx-headers` on `<body>` for htmx; the existing contract and lint stay). Every handler has `@PreAuthorize("hasAuthority('X')")` and the filter chain is the second wall. "S" = SELLER only by the role table; "H" = a HELPER also has it.

| Route | What | Permission | CSRF | Limit |
|---|---|---|---|---|
| `GET /app` | The board: summary strip, paste cards, five columns, footer | `VIEW_BOARD` (S, H) | n/a (safe) | none |
| `GET /app/columns/{column}?after=` | One column, next page (htmx "Show more"; also works as a plain page) | `VIEW_BOARD` | n/a | none |
| `POST /app/orders` | Paste an order (field `text`, hidden `mid`) | `RECORD_ORDER` (S, H) | yes | existing ingestion limit, per tenant |
| `POST /app/payments` | Paste a payment | `RECORD_PAYMENT` (S, H) | yes | same |
| `GET /app/orders/{id}` | Order detail: what was understood, lines, balance, matches with reasons, history | `VIEW_BOARD` | n/a | none |
| `GET /app/payments/{id}` | Payment detail: what was understood, status, suggestions with reasons | `VIEW_BOARD` | n/a | none |
| `GET /app/orders/{id}/review` | Check page for a `NEEDS_REVIEW` order (total form) | `APPROVE_ORDER_TOTAL` (S) | n/a | none |
| `POST /app/orders/{id}/approve` | Set the total, make the order payable, post `ORDER_ACCEPTED` | `APPROVE_ORDER_TOTAL` (S) | yes | `actions-per-user` |
| `POST /app/matches/{id}/confirm` | Confirm a suggestion | `CONFIRM_OR_OVERRIDE_MATCH` (S) | yes | `actions-per-user` |
| `POST /app/matches/{id}/reject` | Reject a suggestion | `CONFIRM_OR_OVERRIDE_MATCH` (S) | yes | same |
| `GET /app/payments/{id}/match` | Picker: open orders in the payment's currency (newest 50) | `CONFIRM_OR_OVERRIDE_MATCH` (S) | n/a | none |
| `POST /app/matches/manual` | Match a payment to an order (fields `orderId`, `paymentId`) | `CONFIRM_OR_OVERRIDE_MATCH` (S) | yes | same |
| `GET /app/matches/{id}/undo` | "Undo this match?" check page | `REVERSE_TRANSACTION` (S) | n/a | none |
| `POST /app/matches/{id}/unmatch` | Unmatch (reversal posting) | `REVERSE_TRANSACTION` (S) | yes | `actions-per-user` |
| `POST /app/payments/{id}/set-aside` | Mark `UNMATCHED` | `CONFIRM_OR_OVERRIDE_MATCH` (S) | yes | same |
| `POST /app/payments/{id}/take-back` | `UNMATCHED` back to `RECEIVED` | `CONFIRM_OR_OVERRIDE_MATCH` (S) | yes | same |
| `POST /app/rematch` | Ask the engine to look again at open payments | `CONFIRM_OR_OVERRIDE_MATCH` (S) | yes | **`rematch-per-tenant`: 3 per minute, 20 per hour** |
| `GET /app/summary?date=` | Daily summary for a day, with previous and next day links | `VIEW_BOARD` (money totals only for `VIEW_LEDGER`, S) | n/a | none |
| `GET /app/books` and `GET /app/books/check` | Reconciliation panel (fragment on demand) | `VIEW_LEDGER` (S) | n/a | cached 5 minutes per tenant |
| `GET /app/export/orders.csv`, `/payments.csv`, `/ledger.csv` (`from`, `to`) | CSV downloads | `EXPORT_DATA` (S); ledger also `VIEW_LEDGER` | n/a | `export-per-tenant`: 6 per 10 minutes |
| `GET /assets/**` | Stylesheet, htmx, favicon | public (no tenant data) | n/a | none |
| `POST /logout` | Exists | signed in | yes | exists |

Failure handling on every route: unknown or other-tenant id gives 404; a wrong permission 403 (existing `ForbiddenHandler`, logged by fixed label); a bad parameter 400 with the fixed error page (never echoing the input); a rate limit 429 with `Retry-After`; a service refusal (`NOT_A_SELLER`, `NOT_SUGGESTED`, `PAYMENT_NOT_FREE`, and the others) is shown as a plain sentence from a fixed table, never as an error page, because "somebody else already did it" is normal.

## How each page stays phone-first, and how htmx is used

- **No JavaScript needed.** Every action is a plain `<form method="post">`; the controller answers with a redirect to the board (or the detail page) carrying a one-sentence notice from the session (a code, rendered from a table). htmx is progressive: when the request has `HX-Request`, the same handler returns only the changed card plus the updated tab counts (out-of-band swap) instead of redirecting. One handler, two answers; the tests run both.
- **Layout.** One column at phone width. The five board columns are sections of a horizontal scroller with CSS scroll-snap (about 90% of the screen wide each), under a sticky row of five chips ("Awaiting 3 | Review 2 | Matched 5 | Flagged 1 | Unparsed 0") that are ordinary anchor links. From about 60em they become five side-by-side columns. No viewport trick (`user-scalable` stays on), touch targets at least 44 px, text at least 16 px, no hover-only controls, no colour-only status (every status has a word).
- **Weight budget.** One stylesheet (about 10 KB), htmx (about 50 KB minified, 16 KB gzipped), no fonts, no images, no icon fonts. Board HTML at most 60 KB with 20 cards per column. A test fails if a budget is exceeded. Assets are served with a content hash in the file name and long cache; pages and CSV are `Cache-Control: no-store` (Spring Security's default, kept).
- **htmx configuration** (extends the existing meta tag): `allowEval:false`, `allowScriptTags:false`, `includeIndicatorStyles:false`, `selfRequestsOnly:true` (existing) plus `historyEnabled:false` (no snapshots in `localStorage`), and a response-handling rule so that 403, 404, 422 and 429 answers are swapped into an `aria-live` notice area instead of being dropped silently. htmx attributes are never built from untrusted text (a lint checks `hx-*` attribute values against a fixed pattern).
- **No polling.** The board does not refresh itself (battery, data cost, and a refresh could swap a card under the seller's thumb). A "Refresh" link reloads it. A paste or an action updates what it touched.
- **Cards.** Newest first, 20 per column, "Show more" by cursor (created time and id, strictly parsed). Absolute times in the tenant's time zone ("03 Nov, 11:02"); no relative times (they need script to stay true).
- **Accessibility basics** (the polish pass is task 12): landmarks and headings in order, labels on every field, focus kept after an action, a visible focus ring, `lang="en"`, `aria-live="polite"` for notices.
- **Language.** All fixed text comes from `messages.properties` through `#{...}` and the locale is fixed to English; French strings are added by dropping in `messages_fr.properties` and enabling the locale (requirement 10.1). No sentence is built by joining fragments in Java.

## The first screen, step by step (what the demo video shows)

Phone width, 390 by 844, signed in as the demo seller:

1. **Top bar.** The workspace name, a small chip "Seller", and a "Sign out" button.
2. **Summary strip.** "Tue 03 Nov: 0 orders, 0 paid, 0 outstanding, 0 flagged, 0 XAF collected."
3. **Paste card.** Two stacked cards: "Paste an order" (open) and "Paste a payment" (closed, one tap opens it; a `<details>` element, no script). Each has a large text box, a line "Paste the message exactly as you received it. It is stored on this server only.", and one big button ("Record order", "Record payment"). The placeholder shows a synthetic example.
4. **Column chips.** Five chips with counts, all 0, "Awaiting payment" selected.
5. **Column.** An empty state: "Nothing is waiting for payment. Paste an order above to start." (each column has its own sentence).
6. **Footer.** "Look again for matches", "Check the books", "Download CSV" (seller only; a helper sees none of these).

The demo, in order: (a) paste an order: a result card says "Order A-1042 recorded: Ana Kamga, phone ending 1234, 1 item, total 5,000 XAF", and the chip "Awaiting 1" appears. (b) Paste the matching SMS: "Payment recorded and matched to order A-1042 automatically", with the reasons as sentences ("The amount is exactly what the order needs. The code A-1042 is on the order and in the payment note. The phone number agrees (ending 1234)."). The order card moves to Matched; Awaiting is 0. (c) Paste an underpayment: it lands in "Needs review" as a suggestion with "Confirm" and "Reject". Tap Confirm: "3,000 applied; 2,000 still to pay"; the order is back in Awaiting as "Part paid". (d) Paste the same SMS again: a Flagged card, "This transaction id was already recorded". (e) Paste free text: an Unparsed card. (f) "Check the books": "The books balance. Owed by customers 2,000 = orders 5,000 less matched 3,000." Task 9's simulators will produce these in one click; here the forms are the whole input.

## Where untrusted text reaches a page, and the defense at each

One rule covers all: **a template prints text only with `th:text`** (escaped), through `Display.text(value, max)` which removes control characters, bidirectional overrides and isolates, zero-width characters and unpaired surrogates, collapses whitespace and cuts by code points with an ellipsis. Stored data is not changed; this is display only. The existing lint already bans `th:utext`, `[( )]`, inline scripts, event handlers, `style` attributes, `javascript:` and `data:` URLs; stop 2 adds: no `th:inline` of any kind, no `th:attr` on `href`/`src`/`action`/`hx-*` with a non-literal beginning, and no `http(s)://` in a template.

| Text | Source | Shown where | Defense |
|---|---|---|---|
| Customer name | chat message (max 100 chars) | order card, detail, CSV | `Display.text(…, 40)` on cards, 100 on detail; CSV neutralised |
| Sender name | SMS | payment card, detail, CSV | same |
| Item labels | chat message | order detail, CSV | same; never used as a link or an id |
| Order reference, payment reference, transaction id | message; database CHECK limits them to `[A-Za-z0-9._:-]`, 64 chars | cards, detail, CSV | still escaped and cleaned; never put in a URL path (ids in URLs are UUIDs) |
| Delivery note | chat message (max 300) | order detail | `Display.text(…, 300)`, `white-space: pre-line` by a CSS class |
| Customer notes | none stored yet | n/a | nothing to show |
| Raw message text | pasted | detail pages of `NEEDS_REVIEW`, `UNPARSED` and flagged items only, seller only (D10) | `PhoneMask.maskText` (digit runs of 7 or more keep the last 4), `Display.text(…, 1000)`, inside `<details>`, labelled "Message as received (not checked)", never autolinked, never a link target, never in a title, alt or `aria-label` |
| Workspace name, user email | provisioning | top bar (workspace name only) | `th:text`; the email is never shown on a board page |
| Reasons and flag text | codes from the scorer; flag reasons are written by task 7's rules | detail and cards | reasons are rendered from **codes** by `ReasonSentences` (a code that is not known renders as "Another reason" and is counted, not shown); a flag's stored sentence is printed escaped and cut |
| Tenant currency | database CHECK `[A-Z]{3}` | amounts | escaped |
| Query and form values (`date`, `after`, `from`, `to`, `mid`, `orderId`) | the request | echoed back never | parsed to a type (date, UUID, strict cursor, enum) and rejected with the fixed 400 page if invalid; not copied into the response |

Further rules: page titles and `<meta>` never contain stored text; untrusted text never appears in `Location` headers, `Content-Disposition` filenames, log lines, audit subjects or htmx attributes; error pages say nothing about input; the existing CSP (`default-src 'none'`, `script-src 'self'`, `style-src 'self'`, `form-action 'self'`, `frame-ancestors 'none'`) stays as the last wall, and a test renders hostile values (`<script>`, `"><img onerror>`, `javascript:`, bidi overrides, `${7*7}`, `th:text` syntax, 5,000-character strings, formula strings) through every page and asserts the markup has no live element or attribute from them.

## CSV export (requirement 4.5, formula neutralisation)

- **Files and columns.** `orders.csv`: id, recorded at (UTC, ISO 8601), status, order code, customer name, phone (masked, `****1234`), total, applied, balance, currency, delivery note. `payments.csv`: id, recorded at, paid at, status, provider, transaction id, sender name, phone (masked), reference, amount, currency, matched order id. `ledger.csv`: transaction id, created at, kind, reference type, reference id, account code, account name, debit, credit, currency. Amounts are written from the integer minor units by integer arithmetic (a decimal string with the tenant's scale, never a float).
- **Neutralisation.** Every **text** cell (anything that came from a message: names, labels, references, notes, codes, even the account name) whose first non-space character is `=`, `+`, `-`, `@`, tab, carriage return, line feed or `|` is prefixed with a single quote, after control characters are removed. Cells the program writes from numbers (amounts, counts) are exempt, so a real `-500` stays a number. All text cells are double-quoted with doubled quotes; lines end `\r\n`; embedded line breaks are replaced by a space. A test feeds every `HostileCorpus` string (task 4) and a list of formula payloads (`=cmd|' /C calc'!A0`, `+1+1`, `-2+3`, `@SUM(1)`, `\t=1`, `\r=1`, `  =1`, the same with full-width characters) through each file and parses the output back with an independent CSV reader.
- **Bounds and headers.** `from` and `to` (dates, at most 366 days, default the last 31), at most 100,000 rows (more is refused with "narrow the dates", not truncated silently), streamed. `Content-Type: text/csv; charset=utf-8`, a fixed file name (`orders-2026-11-03.csv` built from the date only), `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff` (already set), `no-store`. A UTF-8 byte-order mark is written so that spreadsheets show accents (D15). Each download writes an audit row (`EXPORT_CSV`, who, which file, how many rows).
- **Honest limit.** The apostrophe changes the cell: a name that starts with `-` comes back with a leading `'` in some programs. The README and the file page say so.

## `NEEDS_REVIEW` to payable, and its `ORDER_ACCEPTED` posting

A `NEEDS_REVIEW` order has no known total (or a total the prices could not confirm). The seller opens `GET /app/orders/{id}/review`: the order lines (escaped), the total if there is one (the field is pre-filled), and a field for the total in the tenant's currency. They check the text and press "Review", which shows the amount formatted ("Make this order payable for 5,000 XAF?") with a second button. That second request is `POST /app/orders/{id}/approve`.

- **One transaction** (`TenantScope`): lock the order (`FOR NO KEY UPDATE`); require status `NEEDS_REVIEW` (else "already done" sentence); parse the total strictly (digits, and decimals only up to the tenant's scale, between 1 and the maximum, no float anywhere); `UPDATE orders SET total_minor, status = 'PENDING_PAYMENT'`; ensure the four accounts; post `ORDER_ACCEPTED` with `appendWithin` (the same `Postings.orderAccepted` as ingestion; the unique index makes it once); audit `ORDER_APPROVED` (who, with the old and new total as numbers); commit. Any failure rolls back everything and the order stays `NEEDS_REVIEW`. After the commit, the matcher is asked about the order (best effort, like ingestion), so a payment that was already waiting can match.
- **In the database (V018).** `GRANT UPDATE (total_minor) ON orders`; the status trigger additionally allows `NEEDS_REVIEW` to `PENDING_PAYMENT` and nothing else out of `NEEDS_REVIEW`; a new `BEFORE UPDATE OF total_minor` trigger lets the total change **only** in that one move (so no one can change a total at any other time, with or without matches); a **deferred constraint trigger** requires that an order which left `NEEDS_REVIEW` has its `ORDER_ACCEPTED` posting at commit. So even a bug in the application cannot make an order payable without its receivable, or change a total in place.
- **Not covered:** an order cannot go back to `NEEDS_REVIEW`, and a wrongly approved total cannot be corrected in this task (D7). The review step exists to make that mistake unlikely.

## The reconciliation check on the board

A "Books" panel, seller only, loaded on demand (`hx-get`, or the `/app/books` page without script) and cached for five minutes per tenant. It reads the tenant's ledger once and reports plainly:

1. the trial balance (debits minus credits) is zero;
2. accounts receivable equals the totals of the accepted orders minus the active matches;
3. sales equal the totals of the accepted orders;
4. customer credit equals the excess recorded on active matches;
5. the clearing account equals what the active matches received;
6. every posting of a match that has not been reversed belongs to an active match.

Each line shows both sides in the tenant's currency. All pass: "The books balance" with a check mark and the word "balanced". Any fails: the panel turns to a bordered alert ("These figures do not agree: …") with the numbers, a fixed-label error event is logged (no amounts, no names), and the board footer shows "Books: needs attention" until the next check passes. **A mismatch is never hidden, never retried silently, and never "fixed" by the page.** The queries are bounded sums over the tenant's rows; at very large ledgers they are slow, which is why they are on demand (D13). The tests keep their own independent SQL (`MatchInvariants`) and compare it with this one on the same data.

## What the page shows for each match, and for each status

**A match** (on the order and the payment detail pages, and on the cards): a state sentence, the amounts, who and when, and the reasons.

| State | Sentence | Buttons (seller) |
|---|---|---|
| `SUGGESTED` | "Suggested, score 87 of 100. Not applied: nothing is posted until you confirm." | Confirm, Reject |
| `AUTO` | "Matched automatically." | Undo (check page) |
| `CONFIRMED` | "Confirmed by you / by another seller, 03 Nov 11:02." (a manual match: "Matched by you / by another seller") | Undo |
| `REJECTED` | "Rejected by you / retired by the system." | none |
| `REVERSED` | "Undone by you on 03 Nov 11:05. The posting was reversed." | none |

The amounts line reads "3,000 XAF applied to this order" and, when there is an excess, "1,000 XAF is recorded as customer credit". Before Confirm the card shows the amounts the confirmation would apply, computed from the same function; the result sentence after the click reports what was really applied (the balance may have changed).

**Reasons are rendered from codes into sentences**, in a fixed order and with their points:

| Code | Sentence |
|---|---|
| `AMOUNT_EXACT` | The amount is exactly what the order still needs. |
| `AMOUNT_PARTIAL` | The payment is less than what the order needs, so it would be a part payment. |
| `AMOUNT_OVER` | The payment is more than the order needs; the extra would be kept as credit. |
| `REFERENCE_MATCH` | The order code is in the payment note. |
| `REFERENCE_AMBIGUOUS` | The code is on more than one open order, so it proves nothing. |
| `IDENTITY_PHONE` | The phone number agrees (ending 1234). |
| `IDENTITY_NAME_FULL` / `IDENTITY_NAME_INITIAL` | The sender's name agrees with the customer's / agrees in part. |
| `IDENTITY_LAST4` | The last four digits of the phone agree. |
| `TIME_IN_WINDOW` | The payment was made soon after the order. |
| `UNVERIFIED_CAPPED` | The payment has no transaction id, so it can never be matched without you. |

For a **suggestion**, the page also says **why it was not automatic**, by re-scoring the pair now with the same pure scorer and listing the missing conditions among the seven floors in sentences ("The payment has no transaction id", "The amount is not exactly what the order needs", "Recorded by a helper, so you must confirm", "No other order is within 10 points: not true, another order scores close", and so on), labelled "as of now" because facts may have changed (D11). Reasons from an old match are the stored codes, not a re-score.

**Statuses** in plain words, always as text (never colour alone):

| Record | Status | Label shown |
|---|---|---|
| Order | `PENDING_PAYMENT` | Waiting for payment |
| | `PARTIALLY_PAID` | Part paid: 3,000 of 5,000 |
| | `PAID` | Paid |
| | `NEEDS_REVIEW` | Needs your check: the total is not known |
| | `UNPARSED` | Could not be read |
| | `CANCELLED` | Cancelled (nothing sets it yet) |
| Payment | `RECEIVED` | Received, not matched yet |
| | `MATCHED` | Matched to order A-1042 |
| | `UNMATCHED` | Set aside by you |
| | `DUPLICATE` | Repeat of a payment already recorded; not posted |
| | `UNVERIFIED` | No transaction id; needs your confirmation |
| | `UNPARSED` | Could not be read |

## Masked phones

The database holds a keyed hash and the last four digits, never the number. Every page and the CSV show `•••• 1234` (CSV: `****1234`), with a visually hidden "phone ending 1234" for screen readers. No full or partly full number is rendered, logged or exported. The one place a full number can still appear is inside a pasted message, which is handled by D10 (hidden from helpers, masked by digit run for the seller).

## What a HELPER sees and cannot do

| | Seller | Helper |
|---|---|---|
| Board, columns, cards, detail pages | yes | yes |
| Paste an order, paste a payment | yes | yes |
| Daily summary | counts and amounts | **counts only** (orders, paid, flagged) |
| Match reasons and statuses | yes | yes |
| Buttons: confirm, reject, match by hand, undo, set aside, take back, approve a total, look again for matches | yes | **not rendered**; every endpoint answers 403 |
| Check page for a `NEEDS_REVIEW` order | yes | no (403); sees the card "Needs the seller's check" |
| Raw message text | yes (masked, D10) | no |
| Books panel, ledger CSV, orders and payments CSV | yes | no (403) |
| Who confirmed or undid | "you" or "another seller" with time | "a seller" with time |
| A payment they recorded | | is only ever suggested: the card says "Recorded by a helper: the seller must confirm" |

A hidden button is not the defense: the endpoint, the service (which asks the database whether the user is an enabled seller) and the database triggers are.

## Tests (stop 2) and mutation checks

- **Authorization matrix** (generated from the request mappings, so a new route cannot be forgotten): for every route, anonymous (redirect to sign-in), helper, seller of another tenant, seller; with a missing and a wrong CSRF token on every POST; a lint that fails if any handler lacks `@PreAuthorize`, if a state-changing handler maps `GET`, or if a path variable is not a UUID or enum.
- **Isolation:** every new repository statement scoped by tenant, run as the schema owner (the 2a pattern); 404 for another tenant's ids on every detail and action route.
- **Rendering:** the hostile corpus through every page and fragment, plain and htmx; the lint additions; the weight and no-external-host tests; the htmx file's SHA-256 pinned in a test; the CSP and header tests extended to the new routes and to `/assets`.
- **Flows through the real services** (Testcontainers): paste, auto match, suggest, confirm, reject, manual, undo (exact ledger), set aside, take back, rematch with its limit (429 on the fourth call), approve a `NEEDS_REVIEW` order (status, total, posting, audit, rollback on a posting failure, a second approval refused, the matcher then matching a waiting payment), summary figures against hand-computed ones, books check true and (with a forced inconsistency) false.
- **CSV:** the formula payloads, quoting, the row cap, the date bounds, the audit row, no number written as a float (a property test: cell text equals the integer's decimal rendering).
- **V018:** the catalog-driven grant test updated; `total_minor` immutable except in the one move; the commit-time check (an order made payable without its posting is refused); the transition table.
- **Browser** (D18): at 390 by 844, the first-screen walkthrough above, an action with htmx and the same action with JavaScript disabled, the keyboard path, a screenshot of each column.
- **Guards I will mutation-check:** every permission on every route; CSRF on each POST; the 404 for another tenant on each route; `Display` (each class of character it removes, the cut length); `PhoneMask` (each digit-run boundary); the CSV prefix set (each of the eight leading characters), the numeric exemption, the row cap, the date bound; the rate-limit numbers; the approval transaction (status check, bounds, posting, audit, rollback); each V018 trigger condition; the books check (each of the six comparisons); column placement (each precedence rule); the helper-versus-seller field filtering; the page-size cap and the cursor parser; the htmx configuration keys; the weight budgets.

## Weakened defenses and trade-offs, each with its compensation

1. **A third-party script (htmx) runs in the signed-in page.** It is the largest piece of code we do not write. *Compensation:* served from our origin only (no CDN), a pinned version and a pinned SHA-256 checked by a test, `script-src 'self'`, evaluation and script tags in responses switched off, `selfRequestsOnly`, no inline code anywhere, and every action also works without it.
2. **The application role gains `UPDATE (total_minor)` on `orders`.** *Compensation:* column-limited; a trigger freezes it except in the single `NEEDS_REVIEW` move; the move is only possible with a posting by commit; the catalog-driven grant test lists it; audited with old and new values.
3. **A seller can state any total for a `NEEDS_REVIEW` order,** and a wrong total cannot be corrected afterwards in this task. *Compensation:* a read-only check step shows the amount formatted before the second click; seller only; audit; the receivable is reversible by a later cancel feature (D7).
4. **Raw message text, which holds full phone numbers, is shown to the seller** (D10). *Compensation:* seller only, inside a closed `<details>`, digit runs of 7 or more masked to the last four; this is a heuristic and misses numbers written in words or broken by letters; named so the claim "phones are masked" is made only for the structured fields.
5. **Display cleaning changes what is shown, not what is stored.** A name with a bidirectional override looks different from the stored value. *Compensation:* stored text is untouched and exported through CSV neutralisation; the cleaning is one function with tests per character class.
6. **CSV neutralisation alters cells** that start with a formula character (a leading apostrophe), so an export is not byte-exact for them. *Compensation:* said on the page and in the README; it is the price of not executing a stranger's formula.
7. **The books check is on demand and cached for five minutes,** so a fault can be seen up to five minutes late. *Compensation:* the button forces a fresh run; a mismatch is logged; the cost of a live check at every board load would fall on every user.
8. **The Flagged column is partial until task 7:** only duplicate and unverified payments (from their statuses) and any rows already in `flag`. *Compensation:* the page says "Flags from the other rules arrive with the next step"; the board unions both sources by subject so task 7 changes no UI.
9. **No automatic refresh.** Another user's paste appears when the page is reloaded. *Compensation:* a Refresh link; actions refresh what they touch.
10. **Rate limits are in memory, per process** (register item 5 inherited). *Compensation:* bounded key tables; one instance until task 13.
11. **A helper sees customer names and amounts on cards,** because they record the sales. *Compensation:* no phone beyond the last four, no raw text, no ledger, no exports, no money totals.
12. **Scroll-snap columns depend on CSS.** A browser without it shows five stacked sections. *Compensation:* the chips are plain anchors and each column is a real section; nothing needs script.
13. **No cancel or dismiss.** `NEEDS_REVIEW` and `UNPARSED` records stay on the board until a later task (D7). *Compensation:* 20 per column with "Show more"; they sort by time.
14. **One locale (English)** until French strings are added; the catalogue is ready for them.
15. **A new development tool (D18) pulls npm packages** if approved. *Compensation:* outside the Maven build and the repository's runtime, pinned with a lock file, installed with scripts disabled, and said in the notes.

## What I will not build in task 8

Suspicion rules and the resolve-flag button (task 7); the chat and confirmation simulators and the demo seed (task 9); cancelling or editing an order or its lines; applying or refunding customer credit; a ledger browser page (only the CSV and the books check); user management and password pages; saved filters, search and per-customer pages; push notifications; automatic refresh; the polish and keyboard pass beyond the basics above (task 12); the signed machine endpoint (task 11).

## Decisions I need from you, each with my recommendation

| # | Decision | Recommendation | Reason |
|---|---|---|---|
| D1 | **Where each record sits.** Each item appears once. Precedence: `UNPARSED` goes to Unparsed; then anything flagged or a `DUPLICATE`/`UNVERIFIED` payment goes to Flagged; then a `NEEDS_REVIEW` order, an open suggestion (shown on its payment) or a free payment with no order goes to Needs review; then a `PAID` order goes to Matched; then `PENDING_PAYMENT` and `PARTIALLY_PAID` orders go to Awaiting payment. Set-aside payments are behind a "Show set aside" link in Needs review. | **Yes.** | The board answers "what needs my attention now?"; a flagged order must not hide in Awaiting. |
| D2 | **Phone layout.** Scroll-snap columns with sticky anchor chips; five columns side by side on wide screens. | **Yes.** | Swipe is natural on a phone, needs no script, and the chips are real links. |
| D3 | **htmx is vendored** (latest stable 2.x, licence checked, SHA-256 recorded and tested), not fetched from a CDN. I verify the version, licence and hash from the official release in stop 2. | **Yes.** | The CSP allows only our origin; a CDN would add a third party to every page. |
| D4 | **Every action works without JavaScript** (redirect with a notice); htmx only swaps. | **Yes.** | Slow or odd phones (requirement 6.3); a failure of the script cannot lock the seller out of an action. |
| D5 | **Two separate paste forms** (order, payment), no automatic type detection; a hidden random message id per form render, so a double tap records once. | **Yes.** | Guessing the type of an untrusted text decides which parser reads money. |
| D6 | **How the `NEEDS_REVIEW` move is allowed:** (A) a `SECURITY DEFINER` function (needs an exception to the build rule that forbids new definer functions), or (B) a column grant plus triggers as described. New permission `APPROVE_ORDER_TOTAL`, seller only. | **B**, with the new permission. | It follows the pattern V016 used for match amounts, adds no definer function, and the deferred posting check makes an unposted payable order impossible. |
| D7 | **Cancel/dismiss and correcting an approved total** are out of this task. | **Out.** I will name it in `tasks.md` as a later item (it needs the reversal of `ORDER_ACCEPTED`, which `reverseWithin` can do, and a `CANCELLED` transition). | Requirement 6 does not ask for it, and it is a money decision with its own design. |
| D8 | **Time zone and the summary.** Add `tenant.timezone` (IANA name, default `UTC`, validated on read with a fallback to UTC; the demo tenant uses `Africa/Douala`). A day is midnight to midnight there. Orders: parsed orders recorded that day. Paid: of those, the ones now `PAID`. Outstanding: the balance still owed on all open orders now. Flagged: flagged items now. Collected: payments with a transaction id (`RECEIVED`, `MATCHED`, `UNMATCHED`) paid that day, not duplicates, not unverified (shown separately as "not verified"). | **Yes.** | A summary that is wrong at the day boundary is worse than none; the definitions are stated on the page. |
| D9 | **Helper and money totals:** a helper sees counts, not outstanding or collected amounts, not the books panel. | **Yes.** | Least privilege; the role table already withholds the ledger from helpers. |
| D10 | **Raw text.** Shown only to the seller, only on detail pages of `NEEDS_REVIEW`, `UNPARSED` and flagged items, inside a closed `<details>`, digit runs of 7 or more masked to the last four. Never shown to a helper. | **Yes.** | The seller needs the original to decide; requirement 1.4 must hold for what we can structure and be honest about what we cannot. |
| D11 | **Explaining a suggestion:** re-score the pair now with the pure scorer and list the missing conditions, labelled "as of now". | **Yes.** | "Explain every automatic decision in one plain sentence" (product principle); the stored reasons do not hold the floors. |
| D12 | **Two-step buttons:** a check page before **undo** and before **approve a total**; a single click for confirm, reject, set aside, take back (all reversible or harmless). | **Yes.** | The two steps that post to the books or reverse a posting deserve a second look; no `confirm()` dialog (it needs script). |
| D13 | **Books check on demand,** cached 5 minutes, seller only; failure logged and shown until it passes. | **Yes.** | Live check at every load does not scale; hiding a failure is not allowed. |
| D14 | **Limits** (configurable, bounded at startup): `rematch-per-tenant` 3 per minute and 20 per hour; `export-per-tenant` 6 per 10 minutes; `actions-per-user` 120 per minute. | **Yes.** | Rematch scores up to `candidateCap` payments; a stolen session should not be able to hammer the engine or drain the ledger. |
| D15 | **CSV details:** three files; the column lists above; at most 100,000 rows and 366 days; a UTF-8 byte-order mark; prefix `'` after trimming leading spaces for `= + - @ tab CR LF |`; numeric cells exempt; audit row per download. | **Yes.** | Requirement 4.5 and the formula rule in `tasks.md`; the BOM makes accents show in common spreadsheets. |
| D16 | **Exports are `GET` links** (safe, cookie is `SameSite=Strict`, response is not readable cross-site, an audit row is written). | **Yes.** | A POST form for a download is awkward on a phone and adds nothing the cookie policy does not already give. |
| D17 | **Flags are shown read-only** now (union of `flag` rows and status-derived ones, by subject); the "Resolve" button comes with task 7. | **Yes.** | Avoids building a button whose rules do not exist yet. |
| D18 | **Browser testing.** I have Chrome and Edge and Node here. I would add a small script outside the Maven build (`tools/browser-check/`) using `puppeteer-core` against the installed Chrome, with the version pinned and a lock file, `npm ci --ignore-scripts`, run by hand; it takes the screenshots at 390 by 844 and drives the walkthrough with and without JavaScript. Alternative: headless Chrome screenshots only (no dependency, no clicking). | **The pinned script.** | A real click through CSRF, htmx and the notices is the only way to see the phone experience; it never ships and is not in the build. If you say no, I use screenshots and DOM dumps and say that clicks were not tested in a browser. |
| D19 | **Static assets and budgets:** `/assets/**` public, hashed file names, long cache; HTML 60 KB, CSS 12 KB, htmx as released; budget tests. | **Yes.** | Requirement 6.3 (slow connections). |
| D20 | **Paging:** 20 cards per column, cursor "Show more", at most 5 pages (100 cards) per column on the board; older records are reached through the CSV. | **Yes.** | Bounded work and page size; no search in this task. |
| D21 | **Messages catalogue** (`messages.properties`, English only, locale fixed); no sentence assembled in Java. | **Yes.** | Requirement 10.1 without a code change later. |
| D22 | **New permission `APPROVE_ORDER_TOTAL`** (seller only; the helper set stays exactly as it is). | **Yes** (part of D6). | The existing permissions do not mean "state a total". |

If you say "go" without changes I take my recommendation on all twenty-two.

## How the two stops split

- **Stop 2 (after your go),** in this order, each step with its tests before the next: V018 and its trigger tests; the read repositories and the isolation tests; the layout, stylesheet, vendored htmx and the lints; the board and paste pages; the detail pages and the reasons; the seller actions (with and without htmx) and the rematch limit; the approval of a `NEEDS_REVIEW` order; the summary; the CSV export; the books check; the authorization matrix and the rendering tests; the browser check; mutation checks; notes, design register and `tasks.md` (tick task 8 and add the D7 item); then stop with a summary: what each route refuses, what I tested in a browser and what I did not, the mutation table, and the weakened defenses.
- Nothing is committed and no branch is created by me.

**Est. Credits Used:** not visible to me from inside the session; read it from the Kiro status bar.
