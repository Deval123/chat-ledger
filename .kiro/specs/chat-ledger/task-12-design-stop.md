# Task 12 design stop: polish and accessibility

Status: **approved by the owner for 12a only, D1 to D12 as recommended**, with three conditions:
1. none of the CI dependencies (jsoup, axe-core, Chrome for Testing, puppeteer-core) may enter the production image, and the PR says how
   that is proved;
2. the French translation is delivered for the owner's review before merge, with the list of translated keys;
3. the register fix (item 134) is in the first PR.

12b does not start: it gets its own stop 2 after 12a is deployed and smoke-tested, and nothing of 12b is deployed after 2026-11-27.
Branch: `task-12-polish` from `main` at `a52df0a`.

**12a:** deployed 2026-11-15 (release `2104ccb9…`, CI run #83).

**12b: go from the owner (2026-11-21), from `main` at `cdca2bb`**, with five conditions:
1. two pull requests, the first (cancel and dismiss) deployable alone;
2. every V021 guard has a mutant that fails, and a two-thread test covers cancel and correction against a match in both orders;
3. the books check changes as designed, and a mutant that removes an acceptance posting fails `ACCEPTANCE_POSTINGS`;
4. nothing on the money path is relaxed without a register item and its compensation;
5. what does not fit before 2026-11-27 is said at stop 2, with a proposed cut.

No deploy before the owner's go.

**Stop 2:**
- The first pull request is built (branch `task-12b-cancel-dismiss`; `docs/kiro-notes.md`, "Task 12b - stop 2").
- Two changes to section 5, register 177: V021 holds cancel and dismiss only (the total exception and `ORDER_RESTATED` move to V022);
  dismissal has one route per kind.
- **The second pull request (correct a total) is not started; the proposal is to cut it from the freeze** and build it after
  judging. For an unpaid order, cancel and paste again covers the need.

The demo is live at `https://ledger.nkap.dev` (release `116d11eb`, run #55). The submission deadline is 2026-12-04 14:00 PT, 23 days
from this stop. Judges will mostly see the demo flow: the entry page, the board, the scenarios and the books. What a judge sees comes
first.

## What I read, and what it changes

- **tasks.md, task 12:**
  - "Visual design pass, keyboard use, contrast, French string scaffolding";
  - "Cancel, dismiss and correct a total (task 8, D7)": cancel an open order with the reversal of its `ORDER_ACCEPTED`, refused while a
    match is active; dismiss an unread record; correct a total stated by the seller (reverse and post again, one transaction,
    audited). Seller only, with a check page.
  - Requirements 6.3 and 10.
- **Every template** (entry, sign-in, error, the board and its fragments, column, scenarios, order, payment, match by hand, review,
  approve, undo, summary, books, export), **the stylesheet** and **`messages.properties`**. The audit in section 2 is built from them.
- **The money queries:** `BoardRepository.summaryAmounts`, `BooksCheck`, `Postings` and `ChartOfAccounts` (section 3).
- **The order and ledger machinery 12b would touch:**
  - the order status `CHECK` (V004, which already allows `CANCELLED`);
  - `orders_status_transition`, `orders_total_frozen` and `orders_assert_posted` (V018);
  - `uq_ledger_txn_posting` (V016) and `uq_ledger_txn_reverses` (V009);
  - `JdbcLedgerRepository.reverseWithin`;
  - `OrderApprovalService`, `MatchingService.unmatch` and the lock order;
  - the demo row cap and the retire scrub (V020);
  - the board column `CASE`.
- **Facts that shape the design:**
  1. **No accessibility test exists**, and no HTML parser is on the test classpath. `TemplateLintTest` is regex-based and checks
     security rules only.
  2. **All text contrast passes AA already** (lowest pair 6.83:1). The failures are structural: headings, names, the live region,
     focus after a swap, reflow, titles.
  3. **The two "money in" figures are different measures,** not a bug (section 3).
  4. **Only `login.html` and `error.html` still hold hard-coded English.** Every other string goes through `#{...}`. The locale is
     fixed to English (`spring.web.locale-resolver=fixed`). No message with a placeholder contains an apostrophe today.
  5. **A second `ORDER_ACCEPTED` for one order is impossible.** `uq_ledger_txn_posting` is unique on `(tenant, kind, ref_id)` for
     every original posting, including one that was later reversed. So "reverse and post again" needs a new posting kind, not a
     second `ORDER_ACCEPTED`.
  6. **`BooksCheck` would report a false "does not agree"** after any cancel or correction. Its "accepted" side counts every order that
     has an `ORDER_ACCEPTED`, reversed or not.
  7. **A `NEEDS_REVIEW` order may have no total, but `CANCELLED` requires one** (V004 `CHECK`). An order that was never made payable
     cannot be "cancelled", only dismissed.
  8. **The demo cap counts inserts into `orders` and `payment` only.** A status change is an update. New audit, revision or dismissal
     rows are not counted by the cap.

## 1. The split, its order, and what it costs

| Part | Content | Risk | Who sees it |
|---|---|---|---|
| **12a** | The register fix (item 134); the accessibility fixes and their CI checks; the visual pass; the two labels; the French scaffolding | Low: templates, CSS, messages, tests, one CI job. No data or money path changes | Every judge, on every page of the demo flow |
| **12b** | Cancel an open order; dismiss an unread record; correct a seller-stated total | High: migrations, triggers, ledger postings, the books check | A judge only if they try it; every seller after the demo |

**Order: 12a, deployed and smoke-tested, then 12b.** Reasons:
- 12a is what judges see, and it touches no money.
- 12b changes the ledger of a live demo, so it should land on top of a stable 12a.
- 12b keeps its own design stop 2 and its own deploy.

**A freeze date for money paths (D1).** No 12b change is deployed after **2026-11-27**. That leaves a week before the deadline with
the release unchanged, for the rehearsed upgrade and rollback (RUNBOOK sections 5 and 6) and a buffer. If 12b is not ready by then, it
waits until after the submission. The demo is complete without it: tasks.md says "until then a wrong total stays".

**Size.** Relative to work already done:
- **12a** is about half of task 13's stop 2: many small template and CSS edits, two new test classes, one CI job, and a browser walk.
- **12b** is about the size of task 7's stop 2: three migrations, two services, three routes with check pages, new books-check lines,
  concurrency and rollback tests, and a mutation pass over money guards.

These are guesses from scope, not measurements.

## 2. Accessibility (12a)

### Audit against WCAG 2.2 AA (what fails today)

"Fails" means it fails the criterion, or the owner's stated bar where that is stricter. Each finding cites the template it came from.

| # | Criterion | Finding | Pages |
|---|---|---|---|
| A1 | 2.4.1 Bypass blocks | **No skip link.** On the board, the header, the main navigation, the chip bar and the paste section all come before the columns. `<main>` has no `id` to skip to | all |
| A2 | 1.3.1 Info and relationships / owner's rule "one h1" | **The board has no `<h1>`.** It starts at `<h2>` (paste, columns), then `<h3>` cards. Column pages and match by hand go `<h1>` then `<h3>`. The brand in the header is a `<p>` | board, column, picker |
| A3 | 2.4.6 Headings and labels, 4.1.2 Name | **Card buttons repeat the same name.** Confirm, Reject, Set aside, Take back, I checked this, Match by hand and Check the total carry no per-card name or description. A screen-reader list of buttons reads "Confirm, Confirm, Confirm" | board, column |
| A4 | 4.1.3 Status messages | **The notice region is replaced, not updated.** `#notice` is `role="status"`, `aria-live="polite"`, but htmx swaps it out of band with `outerHTML`, and it is `display: none` while empty. Several screen readers do not announce a live region that is itself newly inserted, or that was hidden. The paste result is a second, new `role="status"` element. **Unverified** which readers miss it; the fix removes the doubt | board, every page with the header |
| A5 | 2.4.3 Focus order | **Focus is lost after a card action.** The clicked button is inside the `<article>` that htmx replaces, so focus falls back to `<body>`. The replacement card has `tabindex="-1"`, but nothing focuses it. **Unverified** in a browser; inferred from the markup and htmx 2's behaviour | board, column |
| A6 | 2.4.11 Focus not obscured (minimum) | **The sticky chip bar can hide the focused element.** Below 60em it is `position: sticky; top: 0`, and nothing reserves room for it (`scroll-padding-top`) | board |
| A7 | 1.4.10 Reflow, 1.4.4 Resize text | **Below 60em the five columns are a horizontal scroll strip** (`flex: 0 0 90%`, scroll-snap). At 360 px, and at 200 % zoom on a 1280 px screen (640 CSS px), four of five columns are off-screen, reached by a sideways swipe. The chips jump to them. A region that scrolls sideways is allowed for content that needs two dimensions, but columns of cards do not. Stacking them removes the question | board |
| A8 | 2.4.2 Page titled | **Titles do not tell pages apart.** Every column page has the same title; the approve page reuses the review title; the sign-in and error titles are hard-coded | column, approve, login, error |
| A9 | 1.4.1 Use of colour, 1.3.1 | **Chat bubbles are told apart by side and tint only** (customer or seller; tint contrast 1.06:1 and 1.14:1). A screen reader hears two unlabelled paragraphs | scenarios |
| A10 | 1.3.1 | The demo's chat and feed containers are `<div aria-label>` without a role, so the label is ignored. The books table's `<th>` have no `scope` and the table has no caption | scenarios, books |
| A11 | 3.3.1, 3.3.2, 1.3.1 | **The review form's hint and errors are not tied to the field** (no `aria-describedby`; the error is not announced) | review |
| A12 | 1.3.1 | The match-by-hand page prints phone digits without the spoken form that the board uses (`.sr` "phone ending ...") | picker |

**What passes today** (kept, and pinned by a test so it stays):
- **Text contrast.** Every pair computed from `app.css`:

  | Pair | Ratio |
  |---|---|
  | body text on the page | 15.46:1 |
  | links | 7.04 to 7.50:1 |
  | muted text (lowest: on the done card) | 6.83:1 |
  | button text | 8.04:1 |
  | flag text | 9.30:1 |
  | alert text | 14.49:1 |

- **Non-text contrast** (1.4.11): input borders 3.08:1; the focus ring 7.50:1 on the page and 8.6:1 on white (it sits 2 px outside the
  control).
- **Status and flags are words, not colours.** The status sentence is text; a flag reads "Needs a look: <reason>"; the done card says
  what happened.
- **Focus is visible:** `:focus-visible`, a 3 px outline, never removed.
- **Targets** are at least 44 px (2.5.8).
- **No motion, so nothing for reduced motion** (2.3.3 / user preference).
- **No modal, so no focus trap.** Confirmations are their own pages.
- **Language:** `lang="en"` on every page.
- **Labels:** every visible input has a `<label for>`.

### The fixes

| # | Fix | How, within the CSP (no inline script, no inline style, htmx only) |
|---|---|---|
| A1 | Skip link | First element of `<body>` in every page: `<a class="skip" href="#main" th:text="#{skip.main}">Skip to the main content</a>`, visible on focus (CSS). `<main id="main" tabindex="-1">` |
| A2 | One `<h1>` per page | Board: a visible `<h1>` "Board" with the workspace name. Column and picker pages get `<h2>` group headings so the order never jumps |
| A3 | Per-card names | The visible word stays first and the context is added inside the button as visually hidden text: `<button>Confirm<span class="sr" th:text="#{action.confirm.for(${who}, ${amount})}"></span></button>`. This keeps 2.5.3 (label in name: the accessible name starts with the visible text). It is printed through `th:text` and `@display.text`, never through `th:attr`, so a customer name cannot reach an attribute. Same for Reject, Set aside, Take back, I checked this, Match by hand, Check the total. Each `<article>` gets `aria-labelledby` pointing to its `<h3>` |
| A4 | A stable live region | `#notice` stays in the page at all times. When empty it has no padding and no border, but is never `display: none`. Every out-of-band update swaps its **inner** content (`hx-swap-oob="innerHTML"`), so the region node is never replaced. The htmx `responseHandling` for errors uses `"swapOverride":"innerHTML"`. The paste result goes into `#notice` too, not into a second status element |
| A5 | Focus after a card action | The one-line result card that replaces the clicked card carries `autofocus` and `tabindex="-1"`. htmx 2 focuses an element with `autofocus` in swapped content. If the browser check shows it does not, the fallback is `hx-on`-free: target the swap at the card's result `<p>` and keep the button's form in place. **Unverified until the browser job runs** |
| A6 | Focus not hidden | `html { scroll-padding-top: 4rem; }` below 60em, and `scroll-margin-top` on the column sections, so a focused card or jump target lands below the chip bar |
| A7 | Reflow | Below 60em the columns **stack** (block layout, no sideways scroll). The chip bar stays as jump links with their counts. At 60em and wider, five columns side by side, as today |
| A8 | Titles | Every page title from a key: "Board", "<column name>", "Summary for <day>", "Check an order", "Make it payable", and so on. Sign-in and error from keys too. **Changed during the build (register 164):** the approved text named the customer and the amount in titles; a title is kept in the browser's history and may be synced off the phone, so no title names a customer or carries an amount. Pages are still told apart by purpose (2.4.2); the approve page no longer reuses the review title |
| A9 | Who speaks | Each bubble starts with a visually hidden "Customer:" or "You:" |
| A10 | Groups and tables | The chat and feed become `<section aria-labelledby>` with a heading; the books table gets `<caption>` and `scope="col"` / `scope="row"` |
| A11 | The review form | `aria-describedby` to the hint and to the error, and the error is `role="alert"` |
| A12 | Phone digits | The same `aria-hidden` digits plus `.sr` spoken form as on the board |

**The visual pass, kept small and judge-facing:**
- consistent spacing on the cards and the demo banner;
- the demo entry page reading as a landing page (one short paragraph of what the app does, then the button);
- the board's empty states;
- the summary and books pages laid out as two short cards each.

No new colour pairs are added unless each passes 4.5:1 for text and 3:1 for borders of controls. The contrast test (below) enforces
it. No font, no image, no icon font.

### The checks, in CI

**D2: two layers. A Java structural check in the existing integration phase, and one browser job.**

1. **`AccessibilityIT`** (integration phase, existing Testcontainers stack; **new test dependency: jsoup**). It renders every page of the
   demo flow and of a regular workspace through the existing `Browser` helper, parses the HTML, and checks:
   - one `<h1>`, and headings that never skip a level;
   - the skip link is the first focusable element, and its target exists;
   - one `<main>`; landmarks have names where there is more than one `<nav>`;
   - a unique, non-empty `<title>`; `lang` on `<html>`;
   - every input, select and textarea is labelled (`<label for>`, or `aria-label` from a key);
   - every button and link has a non-empty accessible name, and **no two buttons in one page share an accessible name** (A3);
   - exactly one `role="status"` live region, `#notice`, present on every page, never `hidden` or `display: none`;
   - no positive `tabindex`; every `aria-labelledby` and `aria-describedby` points to an existing id;
   - every `<img>` has `alt`; every `<th>` has `scope`;
   - **no `??key??` left in any page** (a missing message key).

   Each rule has a negative control on an invented page, as `TemplateLintTest` has. Cost in CI: seconds (it reuses the running
   context).
2. **`StylesheetTest`** (unit phase, no new dependency). It reads `app.css` and:
   - computes the contrast of a **declared list of colour pairs**, and fails if a pair or a new colour in the file is not declared;
   - checks text at 4.5:1 and control borders and the focus ring at 3:1;
   - requires a `:focus-visible` rule for every interactive element;
   - forbids `outline: none` and `outline: 0`, and forbids `transition` and `animation` outside a `prefers-reduced-motion: no-preference`
     block;
   - checks that the 60em breakpoint stacks the columns.
3. **`a11y-browser` CI job** (new; `permissions: contents: read`, no secret). It runs the existing `DemoBrowserFixtureIT`
   (`-Dbrowser.demo=true`) and a new `tools/browser-check/a11y-walk.mjs` in headless Chrome. Against the demo flow it checks:
   - **keyboard only:** Tab from the top reaches the skip link first. Tab and Enter, with no mouse, enter the demo, run a scenario,
     confirm a suggestion and open the books. The focused element is always visible (its box is inside the viewport and not under the
     sticky bar);
   - **focus after a card action** lands on the result card (A5);
   - **the live region:** after an action, `#notice` is the same DOM node as before and its text changed (A4);
   - **360 px wide, and 640 px at device scale 2 (200 % zoom of 1280 px):** `document.documentElement.scrollWidth <= innerWidth` on
     every page (no page-level sideways scroll), and all five column headings are reachable without horizontal scrolling;
   - **axe-core** on every page, failing on any `serious` or `critical` violation.

   **New CI dependencies**, all in CI only, none in the image:
   - **jsoup 1.24.1** (Maven, test scope, MIT; scanned by the OWASP check like every Maven dependency);
   - **axe-core 4.14.0** (npm, MPL-2.0, no dependencies), pinned exactly with its `sha512` integrity in `package-lock.json` and installed
     with `npm ci`;
   - **Chrome for Testing 157.0.8103.50**, downloaded from its versioned URL and checked against a SHA-256 written in the workflow,
     cached with `actions/cache` (already pinned by SHA);
   - `puppeteer-core` stays at the pinned 25.16.0.

   **Why a browser and not only jsoup:** keyboard order, focus after an htmx swap, a node kept in place, reflow and zoom are behaviours
   of a rendered page. No static parse can prove them.

   **The npm gap closes in the same change** (open since task 9): the scan job runs the pinned Trivy on
   `tools/browser-check/package-lock.json` (`trivy fs`), failing on a fixable HIGH or CRITICAL, as for the image.

   **CI time:** the job runs in parallel with `build-and-test`. It needs about the same time, about 7 min (it builds and starts the app),
   plus about 1 min for Chrome on a cold cache. The wall time of a push grows by about 1 min.

   **Alternative, not recommended:** skip the browser job and keep only `AccessibilityIT` and `StylesheetTest`. That is cheaper, but A4,
   A5, A6 and A7 would remain unverified. The owner asked for every check in CI.

**What CI still cannot prove** (named, not hidden):
- a real screen reader's speech (NVDA, VoiceOver, TalkBack);
- a real phone's touch behaviour;
- Safari and Firefox (the walk runs Chrome only).

12a's notes will list these as unverified, and I will propose a ten-minute VoiceOver pass by the owner on the live demo before the
deadline.

## 3. The two money labels (12a)

**What each figure is**, read from the code:

| Page | Label today | Computed from | Period | Includes |
|---|---|---|---|---|
| Summary | "Collected (payments with a transaction id): 36,400 XAF" (board strip: "Collected: ...") | `payment.amount_minor` where `tx_id IS NOT NULL` and status `RECEIVED`, `MATCHED` or `UNMATCHED` | **one local day** (`paid_at` in the tenant's day) | every confirmed payment of the day, **matched or not**, set aside included; repeats (`DUPLICATE`) and unverified ones excluded |
| Books | "Mobile money received equals the matches", 18,000 XAF | net debit of account **1000** "Mobile money clearing", compared with `applied + excess` of the active matches | **all time** | only payments **with an active match**, and the whole payment, including the excess an overpayment leaves as customer credit (2100) |

**Not a bug.** The figures differ for two legitimate reasons:
- Collected counts confirmations that are not matched yet;
- the two cover different periods.

A reversed match nets to zero on account 1000 and is still counted once in Collected. That is correct: the money did arrive. Nothing
in what is computed changes.

**D3: wording that says what each number is, and a sentence linking the two:**
- **Summary:** "Collected on {day}: {amount}". Below it: "Every payment confirmed by a transaction id that day, matched to an order or
  not." Then a link to the books ("Compare with the books").
- **Board strip:** "Collected today: {1}" (adds "today").
- **Books, the line:** "Mobile money matched to orders, since the start (overpayments included), equals the matches".
- **Books, under the table:** "Only payments matched to an order are counted here, since the first day. The summary's 'Collected'
  counts every confirmed payment of one day, matched or not, so the two are different numbers." Then a link to the summary.

A judge who reads both pages sees why the numbers differ, on both pages, in one sentence each.

## 4. French scaffolding (12a)

**D4: scaffolding, a complete-or-off rule, and translation of the demo path only.**
- **Bundle.** `messages.properties` stays the English default. Add `messages_fr.properties` (UTF-8, as the current file). Keys are named
  by meaning (`board.title`, `action.confirm.for`), never by English text.
- **The last hard-coded strings move to keys:** about nine in `login.html` and four in `error.html`.
- **Escaping stays on.** Text is only ever printed through `th:text` / `#{...}`. `TemplateLintTest` already forbids `th:utext`, `[(...)]`
  and `th:inline`. Parameters such as names go through `@display.text` and are escaped with the rest of the sentence.
- **Apostrophes.** Spring formats a message that has parameters with `MessageFormat`, which removes a single `'`. French uses
  apostrophes everywhere ("l'ordre"), so the French file writes the typographic apostrophe `’` (U+2019). A test refuses an ASCII `'` in
  any French message, and refuses one in any English message that has a parameter.
- **The language a page shows: still fixed to English in production.** The locale resolver stays `fixed` (`en`). A French browser must
  never see half-translated pages. The resolver can only become Accept-Language, limited to `en` and `fr`, once the French file covers
  every key. `StartupGuards` refuses a non-fixed resolver while the French key set is incomplete. Then `<html th:lang="${#locale.language}">`.
- **Missing keys are caught in CI:**
  1. **`MessageKeysTest`** (unit): every literal `#{key}` in every template exists in `messages.properties`. The dynamic families
     (`status.*`, `notice.*`, `flag.*`, `reason.*`, `column.*`) are already checked from their enums by `NoticeAndMessagesTest`, which is
     extended with the new keys. `messages_fr.properties` has no key that English lacks, and the same placeholders per key (`{0}`,
     `{1}`). The completeness of French is reported, and enforced only by the startup guard above.
  2. **`AccessibilityIT`** fails on any `??key??` in a rendered page.
- **What I translate now:** the demo path only (the entry page, the board chrome, statuses, actions, notices, the scenarios page, the
  books and the summary), about 150 of about 320 keys. It is cheap, and it shows the scaffolding works. The owner reviews the French
  wording; I would not ship money wording in French without that review. The rest waits, and French stays off in production until
  done.

## 5. Cancel, dismiss, correct a total (12b), design only

### What each one is

| Action | Applies to | Effect | Ledger |
|---|---|---|---|
| **Cancel an order** | an order in `PENDING_PAYMENT` with **no active match**, no applied amount (under the lock) | status `CANCELLED`; its open suggestions are rejected on the seller's behalf; it leaves the board and the matcher | the `ORDER_ACCEPTED` is reversed through `reverseWithin` (receivable and sales back to zero for it) |
| **Dismiss a record** | an `UNPARSED` order, an `UNPARSED` payment, or a `NEEDS_REVIEW` order (none of them has a posting or a match) | a row in a new append-only `record_dismissal` table; the record leaves the board; a dismissed `NEEDS_REVIEW` order can no longer be approved | none |
| **Correct a total** | an order made payable by the seller (`ORDER_APPROVED` in its audit trail), in `PENDING_PAYMENT`, with **no active match** | `total_minor` changes from the stated old value to the new one; a row in a new append-only `order_total_revision` table; open suggestions are rejected; the matcher looks again after commit | the current acceptance posting is reversed through `reverseWithin`, and a new posting **`ORDER_RESTATED`** (debit receivable, credit sales, the new total) is written, with `ref_id` = the revision id |

**Why these limits:**
- **A partly paid or paid order is not cancelled or corrected here.** The seller unmatches first (one action, already audited), so
  there is never a money posting to split.
- **Dismiss, not cancel, for records that were never payable.** `CANCELLED` requires a total (V004), and those records have no posting
  to reverse.
- **Correct only seller-stated totals.** tasks.md says so. A parsed total that is wrong is cancelled, and the message pasted again.
- **No undo of a dismissal or a cancel** in this task (append-only; named in the register).
- **At most three corrections per order** (D9). It bounds the posting chain and the demo's growth.

### Every place untrusted input or a state change arrives

| Route | Input | Checks |
|---|---|---|
| `GET /app/orders/{id}/cancel` | a path UUID | seller role (`@PreAuthorize`), tenant from the principal, order must exist in the tenant (404 otherwise, as everywhere); shows the order, its total and "This cannot be undone", writes nothing |
| `POST /app/orders/{id}/cancel` | path UUID, CSRF token | CSRF; the per-user action rate limit; the service re-checks the role in the database (`isSellerWithin`) and every condition under the lock |
| `GET /app/orders/{id}/total` | path UUID | as above; shows the current total, a field for the new one |
| `GET /app/orders/{id}/total/check` | path UUID, the new total (text, 1 to 30 characters, parsed by the review page's parser: digits, grouping, no decimals for XAF), the old total (integer) | shows "From X to Y" formatted, writes nothing |
| `POST /app/orders/{id}/total` | path UUID, `expected` (old total, integer), `total` (new, integer), CSRF token | bounds `1 .. OrderLimits.MAX_MINOR`, `total != expected`; under the lock, `total_minor` must still equal `expected` (an optimistic check, so a replay or a concurrent change is refused, never applied twice) |
| `POST /app/records/{kind}/{id}/dismiss` (with its check page) | `kind` from a fixed list (`order`, `payment`), path UUID, CSRF token | role, rate limit, and under the lock: the record is `UNPARSED`, or `NEEDS_REVIEW` for an order, and not dismissed yet |

**The rest of the input surface:**
- **Every form is a plain form** (POST, then redirect), with the htmx variant for the board card. No JavaScript is needed.
- **No route takes free text** other than the new total, which is parsed and bounded, never echoed raw.
- **Nothing reaches a log but ids and codes** (`OrderEvents`, as `MatchEvents` does).

### Who, and in which transaction

- **Who: the seller only.** Helpers and the owner role get 403 from the routes. The service refuses `NOT_A_SELLER` from the database.
  The authorization matrix test (`HandlerCoverageTest`, `AuthorizationMatrixIT`) gains the three routes.
- **One transaction per action, with the lock order unchanged:**
  - **Cancel and correct:** lock the order (`FOR UPDATE`), then its open suggestions. No payment lock is needed, because there is no
    active match.
  - **An automatic match racing a cancel:** `apply` locks payment then order, so it waits on the order lock. After the cancel commits,
    `apply` re-reads `CANCELLED` and refuses `ORDER_NOT_OPEN`. No deadlock: the cancel never takes a payment lock.
  - **Dismiss** locks the one record.
- **Cancel, in order:**
  1. seller check;
  2. lock;
  3. require `PENDING_PAYMENT` and `appliedWithin = 0`;
  4. reverse the order's live acceptance posting (`ORDER_ACCEPTED`, or its latest `ORDER_RESTATED`);
  5. status `CANCELLED`;
  6. reject its open suggestions;
  7. audit `ORDER_CANCELLED`;
  8. commit.

  Any failure rolls back all of it.
- **Correct a total, in order:**
  1. seller check;
  2. lock;
  3. require `PENDING_PAYMENT`, `appliedWithin = 0`, an `ORDER_APPROVED` audit row, `total_minor = expected`, fewer than three
     revisions;
  4. insert the `order_total_revision` row (old, new, who, when);
  5. reverse the live acceptance posting;
  6. update `total_minor`;
  7. post `ORDER_RESTATED` for the new total, referencing the revision;
  8. reject the open suggestions;
  9. audit `ORDER_TOTAL_CORRECTED`;
  10. commit. After the commit, the matcher looks again, best effort, as after an approval.
- **Idempotency:**
  - **A second cancel** finds `CANCELLED` and refuses `NOT_CANCELLABLE`. Even if a Java check were wrong, `uq_ledger_txn_reverses` makes
    a second reversal impossible.
  - **A replayed correction** finds `total_minor != expected` and refuses `TOTAL_CHANGED`.
  - **A second dismiss** finds the dismissal row (`UNIQUE (tenant_id, subject_type, subject_id)`) and is refused.
  - Each is tested by running it twice, with the existing `twice(...)` pattern (the second run changes no fingerprint).

### The database's own guards (V021)

1. **`orders_status_transition` gains one named move:** `PENDING_PAYMENT -> CANCELLED`, only if the order has no active match. The
   trigger checks `match` itself, so a code path that forgets is refused by the database.
2. **`orders_total_frozen` gains one named exception:** a total change while the status stays `PENDING_PAYMENT`. It is allowed only if
   a `order_total_revision` row for this order exists in this transaction with `old_total = OLD.total_minor` and
   `new_total = NEW.total_minor`, and there is no active match. Every other total change stays refused, for every role.
3. **A new deferred check, at commit:**
   - a `CANCELLED` order has no unreversed acceptance posting;
   - a `PENDING_PAYMENT` order has exactly one unreversed acceptance posting (`ORDER_ACCEPTED` or `ORDER_RESTATED`) whose amount equals
     `total_minor`.
4. **`ORDER_RESTATED`** is a new `ledger_txn.kind`, unique per revision (`uq_ledger_txn_posting` extended to it, with
   `ref_id` = the revision id). `uq_ledger_txn_reverses` already makes each posting reversible once.
5. **`order_total_revision` and `record_dismissal`** follow the project's table rules:
   - append-only (no `UPDATE` or `DELETE` grant; the immutability trigger), `tenant_id` with the composite foreign keys, and RLS;
   - added to `IsolationRegistry`, so the per-table probe proves tenant A cannot read tenant B;
   - added to the `NoBackDoorTest` inventory.
6. **A dismissed `NEEDS_REVIEW` order cannot be approved:** the approval trigger path (`NEEDS_REVIEW -> PENDING_PAYMENT`) refuses if a
   dismissal row exists, and `OrderApprovalService` checks it under the lock too.

### The books check, changed on purpose

`BooksCheck`'s "accepted" becomes the sum of the totals of the orders that have a live (unreversed) acceptance posting. Today's query
would report "does not agree" after the first cancel. Two new lines are added:
- **`ACCEPTANCE_POSTINGS`:** live acceptance postings, exactly one per payable order;
- **`CANCELLED_CLEAR`:** cancelled orders carry nothing on receivable.

### Demo mode

- **Allowed in demo workspaces.** A judge may try them, inside the leased workspace only (the tenant comes from the principal; RLS and
  the composite keys refuse any other).
- **The 300-row cap is unaffected.** Updates are not counted; revision and dismissal rows are bounded by the per-user action limit and
  by three revisions per order.
- **The retire scrub** already overwrites the text of every order of a retired workspace, cancelled or not; the revision rows hold
  amounts only, no text.
- **Scenarios** never cancel or correct, so the seeded story is unchanged.

### What the board and pages show afterwards

- **A cancelled order** leaves every column. The column `CASE` sends `CANCELLED` to none, checked before the "flagged" branch, so an
  open flag cannot pull it back. Its own page says "Cancelled on {date} by the seller", with the reversal shown in its postings.
- **A dismissed record** leaves the board (the board queries exclude dismissed ids). Its page says "Dismissed on {date}".
- **A corrected order** shows its new total, and "Total corrected from X to Y on {date}" (the latest revision).
- **The summary:** "orders recorded" and "flagged" exclude cancelled and dismissed records. "Still owed" already excludes them.
- **The notices:** `ORDER_CANCELLED`, `ORDER_TOTAL_CORRECTED`, `RECORD_DISMISSED`, and a refusal sentence per reason
  (`NOT_CANCELLABLE`: "This order has a payment matched to it. Unmatch it first.", and so on).

### Threat model (12b)

| Threat | Defence |
|---|---|
| A helper or the owner role cancels or rewrites a receivable | routes are seller-only; the service asks the database; the matrix test covers the three routes |
| A forged cross-site request | CSRF on every POST; `SameSite=Strict` session cookie |
| Another tenant's order by id | tenant from the principal only; RLS; 404 for an unknown or foreign id; the isolation probe on both new tables |
| A double click or a replay applies twice | the status and expected-total checks under the lock; unique indexes on reversal, dismissal and posting |
| Cancel and match at the same moment | one order lock; `apply` re-reads under the lock; the trigger refuses `CANCELLED` with an active match; a concurrency test with two threads, both orders |
| A correction that hides money | it is only allowed with no applied amount; the revision row and the audit row are append-only; the old posting is reversed, never edited |
| The books silently drift | the deferred posting check at commit; the books check with two new lines; `MatchInvariants` extended with "a cancelled order has no live receivable" |
| An amount out of range or malformed | the review page's parser; bounds `1 .. MAX_MINOR`; `total != expected` |
| Free text in logs | ids and codes only |
| Demo abuse (thousands of revisions) | the per-user action limit; three revisions per order; the workspace lease |

### Mutation checks (12b)

Each guard is mutated and must be killed by a test:
- the seller check;
- the `PENDING_PAYMENT` requirement;
- the `appliedWithin = 0` requirement, both in Java and in the trigger;
- the `ORDER_APPROVED` requirement;
- the expected-total check;
- `total != expected`;
- the bounds;
- the three-revision cap;
- each step's place in the transaction (fail after each step: nothing left behind);
- the reversal of the right posting (latest live one);
- the `ORDER_RESTATED` amount;
- the suggestion rejection;
- the new trigger move and exception;
- the deferred check (both rules);
- the dismissal uniqueness and the "dismissed cannot be approved" rule (Java and trigger);
- the board `CASE` order;
- the summary exclusions;
- the two new books-check lines and the new "accepted";
- the demo tenant scope.

12a's mutation pass covers every `AccessibilityIT`, `StylesheetTest` and `MessageKeysTest` rule (each made to pass always, or removed),
and the apostrophe rule.

## 6. Register fix (in 12a's first PR)

Item 134 becomes:

> **Every deploy interrupts the demo briefly and signs visitors out**: measured on 2026-11-11, about 25 to 30 s without a 200 on
> `/health` and 35 to 40 s end to end (upgrade 25 samples, rollback 23 samples, one-per-second probe; `docs/kiro-notes.md`, "Task 13 -
> rollback rehearsal"). An upgrade **with a migration** has not been rehearsed. | A second web container does not fit the memory
> budget | Deploys outside judging hours; rollback by the previous digest (rehearsed); the rate-limit counters restart at full value

## 7. Weakened defenses, scope, decisions, tests

### Weakened defenses and their compensation

**12a:**
1. **Third-party code in CI grows:** Chrome for Testing, axe-core, and jsoup. *Compensation:*
   - all pinned (version plus checksum or lockfile integrity);
   - the browser job has `contents: read` and no secret;
   - none of them reaches the image;
   - the npm lockfile is scanned by the pinned Trivy, and so is jsoup (`rootfs` mode). **Corrected during the build:** this said
     "jsoup by the OWASP check", which skips test scope (register 167).
2. **Customer and sender names now also appear inside button text** (visually hidden), so a name is printed in more places.
   *Compensation:*
   - only through `th:text` and `@display.text` (cut to 40 characters, control characters removed), never in an attribute;
   - a template lint rule refuses `th:attr` with `aria-` built from data;
   - the CSP is unchanged.
3. **The French file can drift from the English one.** *Compensation:* the key and placeholder parity test, the apostrophe test, and
   French off in production until complete (the startup guard).

**12b:**
1. **The total freeze is relaxed:** a payable order's total can change, in one named move. *Compensation:*
   - only without an applied amount, with a revision row in the same transaction (checked by the trigger), and audited;
   - the old posting reversed, never edited;
   - at most three times.
2. **The order status machine gains `PENDING_PAYMENT -> CANCELLED`.** *Compensation:* the trigger refuses it with an active match; the
   reversal is checked at commit.
3. **The books check's definition of "accepted" changes.** *Compensation:* two new lines that check the new invariants; the property
   tests extended; the old definition kept as a test oracle on workspaces with no cancel.
4. **No undo for a cancel or a dismissal.** *Compensation:* the check page says it; the data is kept (the order and its reversal stay
   visible on its page); a seller who regrets it pastes the message again.
5. **Visitors to the demo can reverse receivables in their leased workspace.** *Compensation:* only there, bounded, and scrubbed at
   retire like everything else.

### What I will not build in task 12

- A language switch in the interface; French on in production.
- Undoing a cancel or a dismissal.
- Cancelling or correcting a partly paid or paid order.
- Correcting a parsed (not seller-stated) total.
- Dark mode, icons, a font.
- A screen-reader run in CI.

### Decisions

| # | Decision | Recommendation | Reason |
|---|---|---|---|
| D1 | Split and order | **12a first (one stop 2, two PRs: the accessibility, labels and register fix; then the French scaffolding), deployed and smoke-tested; then 12b with its own stop 2. No 12b deploy after 2026-11-27** | judges see 12a; 12b touches money on a live demo; a week of freeze before the deadline |
| D2 | Accessibility checks in CI | **`AccessibilityIT` (jsoup) + `StylesheetTest` + an `a11y-browser` job (Chrome for Testing, puppeteer-core, axe-core), plus Trivy on the npm lockfile** | static checks cannot prove keyboard, focus, live region, reflow; the owner wants every check in CI |
| D3 | The money labels | **Reword both and link them, as in section 3; compute nothing differently** | not a bug; different measures |
| D4 | French | **Bundle and parity tests now; demo path translated (owner reviews); production stays English until complete (startup guard)** | no mixed-language pages for judges |
| D5 | Board layout below 60em | **Stack the columns; chips stay as jump links** | 1.4.10 reflow and 200 % zoom without sideways scrolling |
| D6 | Per-card button names | **Visually hidden context inside the button, from keys, through `th:text`** | name starts with the visible text (2.5.3); no data in attributes |
| D7 | The live region | **One stable `#notice`, inner swaps only, never hidden** | 4.1.3 without stealing focus |
| D8 | Cancel scope | **`PENDING_PAYMENT` with no applied amount; refused otherwise ("unmatch first")** | no split money postings |
| D9 | Correct a total | **Seller-stated totals only; `expected` old total required; `ORDER_RESTATED` posting per revision; at most three per order** | replay-safe; unique postings stay unique; bounded |
| D10 | Dismiss | **A new append-only `record_dismissal` table for `UNPARSED` orders and payments and `NEEDS_REVIEW` orders; a dismissed review cannot be approved** | no change to the payment status machine or the `CHECK`s; nothing to reverse |
| D11 | Demo mode | **All three allowed in a leased workspace, with the action limit and the revision cap** | judges may try it; the cap is unaffected |
| D12 | Undo | **None in task 12** | append-only; regret means a new paste |

### Verification plan

**12a, in CI:**
- `AccessibilityIT`, `StylesheetTest`, `MessageKeysTest` and the extended `NoticeAndMessagesTest`, each rule with its negative control;
- the `a11y-browser` job (keyboard walk, focus, live region, 360 px and 200 %, axe-core);
- Trivy on the npm lockfile;
- the existing render ITs updated where markup changed (`HtmxContractIT` for the inner swap, `TemplateLintTest` for the new rules);
- the mutation pass over every new rule.

**12a, on the VPS after deploy:** the smoke script (25 checks, after `web (healthy)`); a ten-minute VoiceOver pass by the owner on the
entry page, the board and one scenario (unverified until then).

**12b, in CI:**
- `OrderCancelIT`, `OrderTotalCorrectionIT`, `RecordDismissalIT`, each with:
  - the twice-run idempotency;
  - a rollback after each step (hooks, as in `OrderApprovalService`);
  - the refusals per reason;
  - the authorization matrix;
- `CancelMatchConcurrencyIT`: two threads, cancel against an automatic match and against a confirm, both orders of arrival;
- the V021 trigger tests run as the owner role (refused moves, the deferred check);
- `IsolationRegistry` and `NoBackDoorTest` extended;
- `MatchingBooksPropertyIT` gains cancel and correct actions, and its invariants (the books, a repeated decision changes nothing,
  rematch settles) must hold;
- `BooksCheckIT` with cancels and corrections;
- the mutation pass listed in section 5.

**Unit phase with Docker stopped, then the full verify, for both parts.**

### Unverified in this proposal

- How htmx 2 handles `autofocus` in a swapped card (A5): the browser job decides, with the fallback named.
- Which screen readers miss the replaced live region today (A4); the fix makes the question moot, but the claim is inferred.
- The CI time of the browser job (estimated from `build-and-test`'s 7 min).
- That Chrome for Testing 157 and axe-core 4.14 are still current on the day of the build (they will be re-resolved and pinned then).
- The cost of each part, beyond the size estimate above.

If you say "go" without changes, I take my recommendation on D1 to D12 and start 12a.
