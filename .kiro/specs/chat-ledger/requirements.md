# Requirements: chat-ledger

Working name: **chat-ledger** (rename freely).

## Problem

Millions of small sellers run their shop in WhatsApp chats. Orders arrive as free text.
Customers pay by mobile money and prove it with an SMS or a screenshot. The seller then
matches orders to payments by hand, loses track of who paid, cannot tell a real
confirmation from a fake one, and ends up with no records a bank could trust.

## Product in one sentence

chat-ledger turns a WhatsApp order message and a mobile money confirmation into a matched,
auditable double-entry record, and flags anything suspicious.

## Users

- **Seller**: owns the shop, pastes chats and confirmations, reviews the board.
- **Helper**: a relative or employee who records sales for the seller (same tenant, limited rights).
- **Judge / demo visitor**: uses a seeded demo tenant with simulated data only.

## Glossary

- **Order**: structured request extracted from a chat message.
- **Payment**: structured confirmation extracted from a mobile money SMS.
- **Match**: link between one order and one or more payments.
- **Ledger entry**: immutable double-entry posting.
- **Minor unit**: smallest currency unit stored as an integer (XAF has scale 0).

## Non-goals (contest build)

- No real WhatsApp Business API and no real mobile money API. Input is pasted or simulated.
- The system never moves money and never initiates a payment.
- No financial or tax advice.

---

## Requirement 1: Capture an order from chat text

**User story:** As a seller, I want to paste a WhatsApp order message so that it becomes a structured order.

Acceptance criteria:
1. WHEN a chat message is submitted, THE system SHALL extract customer name or phone, items, quantities, total amount, currency and delivery note, and create an order in status `PENDING_PAYMENT`.
2. IF the message has no total, THEN THE system SHALL compute it from the catalog when every item is known, otherwise set status `NEEDS_REVIEW`.
3. WHEN the same external message id is submitted twice, THE system SHALL keep exactly one order.
4. THE system SHALL show phone numbers masked except the last 4 digits, in the UI and in logs.
5. IF the message cannot be parsed, THEN THE system SHALL store the raw text with status `UNPARSED` and SHALL NOT drop it.

## Requirement 2: Capture a payment from a confirmation SMS

**User story:** As a seller, I want to paste a mobile money confirmation so that it becomes a payment record.

Acceptance criteria:
1. WHEN a confirmation text is submitted, THE system SHALL extract provider, transaction id, amount, currency, sender (masked), and timestamp.
2. THE system SHALL support provider formats through configurable templates, and ship with at least two synthetic formats for the demo.
3. IF no template matches, THEN THE system SHALL store the raw text with status `UNPARSED`.
4. WHEN a transaction id already exists for the tenant, THE system SHALL mark the new record `DUPLICATE` and SHALL NOT create a second ledger posting.
5. WHEN a payment arrives as a screenshot or forwarded text with no provider transaction id, THE system SHALL mark it `UNVERIFIED`.

## Requirement 3: Match orders and payments

**User story:** As a seller, I want orders and payments matched automatically so that I know who has paid.

Acceptance criteria:
1. WHEN a new order or payment is recorded, THE system SHALL score every open candidate pair with an explainable score from 0 to 100.
2. WHEN the best score is at or above the auto-match threshold, THE system SHALL match without asking.
3. WHEN the best score is between the suggestion threshold and the auto-match threshold, THE system SHALL propose the match and wait for confirmation.
4. THE system SHALL show, for each match, the reasons that produced the score.
5. WHEN a payment is lower than the order total, THE system SHALL record a partial match and keep the order `PARTIALLY_PAID`.
6. WHEN a payment is higher than the order total, THE system SHALL mark the order `PAID` and record the excess as `OVERPAID` with the amount.
7. THE seller SHALL be able to override, unmatch, or manually match, and every override SHALL be recorded with who and when.

## Requirement 4: Double-entry ledger

**User story:** As a seller, I want trustworthy records so that a lender or accountant can rely on them.

Acceptance criteria:
1. THE system SHALL post every business event as balanced entries: total debits equal total credits per transaction.
2. THE system SHALL never edit or delete a posted entry. Corrections SHALL be reversal entries.
3. THE system SHALL keep ledger data separate per tenant.
4. THE system SHALL store amounts as integers in minor units and SHALL NOT use floating point for money.
5. THE system SHALL export the ledger and the order and payment status as CSV.

## Requirement 5: Suspicion flags

**User story:** As a seller, I want fake or odd confirmations flagged so that I do not release goods for nothing.

THE system SHALL raise a flag, with a plain-language reason, in each of these cases:
1. Duplicate transaction id.
2. Screenshot or forwarded text with no matching provider SMS (`UNVERIFIED`).
3. Amount differs from the order by a small margin that looks deliberate (for example a missing digit).
4. Sender name does not resemble the customer on the order.
5. Payment timestamp is before the order or far outside the expected window.
6. Same sender used a different name on a recent order.

Flags SHALL be advisory. The seller decides.

## Requirement 6: Board and daily summary

**User story:** As a seller, I want one screen that shows what needs my attention.

Acceptance criteria:
1. THE system SHALL show a board with: Awaiting payment, Needs review, Matched, Flagged, Unparsed.
2. THE system SHALL show a daily summary: orders, paid, outstanding, flagged, and total collected.
3. THE UI SHALL work on a phone-width screen and under slow connections (no heavy client bundle).

## Requirement 7: Demo simulators

**User story:** As a judge, I want to see the whole flow without any real account.

Acceptance criteria:
1. THE system SHALL include a chat simulator that looks like a WhatsApp conversation and a confirmation feed simulator.
2. THE system SHALL include one-click scenarios: happy path, underpayment, overpayment, duplicate confirmation, screenshot-only payment, wrong sender name, late payment.
3. THE demo tenant SHALL contain only synthetic data and no real phone numbers.

## Requirement 8: Extraction pipeline

Acceptance criteria:
1. THE system SHALL extract with deterministic parsers first.
2. WHERE an AI extractor is configured, THE system SHALL call it only when the parsers fail, with a timeout and a schema-validated output.
3. THE system SHALL cross-check any AI-extracted amount against the message text and SHALL NOT trust it alone.
4. THE default deployment SHALL run with no AI extractor and no data leaving the server.

## Requirement 9: Security and privacy

Acceptance criteria:
1. THE system SHALL require authentication, and isolate every query by tenant.
2. THE system SHALL limit input size, rate-limit ingestion endpoints, and validate every field.
3. WHERE a machine endpoint receives messages, THE system SHALL require an HMAC signature.
4. THE system SHALL keep secrets out of the repository and out of logs.
5. THE system SHALL escape all rendered user text and use CSRF protection on forms.
6. THE system SHALL let a tenant export its data, and SHALL let a tenant delete its personal data: customers, customer and sender names, hashed phone numbers, and the raw text of chat messages and confirmations. Deleting personal data SHALL NOT delete or change any ledger transaction or entry. The ledger holds only account identifiers, amounts, dates and opaque references, and no personal data, so it is kept for the tenant's records (requirement 4.2).

## Requirement 10: Language and money

Acceptance criteria:
1. THE UI SHALL be in English, built so French strings can be added without code changes.
2. THE default currency SHALL be XAF, with currency scale configurable per currency.
