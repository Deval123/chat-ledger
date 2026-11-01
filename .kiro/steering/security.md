# Security steering

Security outranks speed, scope and elegance. Do not accept a weaker defense to save time.
If a decision weakens a defense, even slightly, name it in the pull request and compensate for it.

Rules:

- Every database query is scoped by tenant. A test proves tenant A cannot read tenant B.
- Ledger entries are append-only. Corrections are reversal entries.
- Validate every input: size, type, range, encoding. Reject, do not guess.
- Encode all output. Use CSRF protection on forms. Set a strict content security policy.
- Secrets only from environment variables. Never in the repository, logs, or test fixtures.
- Phone numbers are stored hashed with a per-tenant salt, plus the last 4 digits for display.
- Machine endpoints require an HMAC signature and an idempotency key.
- Rate-limit ingestion per tenant.
- AI extraction is off by default. Never trust its output for money without a cross-check against the source text.
- Dependencies: pin versions, run a vulnerability check in CI.
