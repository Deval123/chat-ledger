# Tasks: chat-ledger

Work top to bottom. Each task ends with passing tests. Reference the requirement in the commit message.

- [ ] 1. Project skeleton
  - Maven multi-module (`ledger-core`, `extraction`, `matching`, `suspicion`, `web`), Java 21, Spring Boot, Flyway, PostgreSQL via Docker Compose.
  - Public repo files: LICENSE (Apache-2.0), README (English), `.kiro` committed and not ignored.
  - _Requirements: 4, 9_

- [ ] 2. Ledger core
  - [ ] 2.1 Accounts, transactions, entries, balance invariant, reversals.
  - [ ] 2.2 Property-based tests: balanced transactions, reversal returns to the same balances.
  - [ ] 2.3 Database constraints: no update or delete on entries.
  - _Requirements: 4_

- [ ] 3. Tenancy and security base
  - [ ] 3.1 Tenant model, authentication, roles (seller, helper).
  - [ ] 3.2 Tenant isolation tests across every repository.
  - [ ] 3.3 CSRF, output encoding, content security policy, body size and rate limits.
  - _Requirements: 9_

- [ ] 4. Order extraction
  - [ ] 4.1 Order parser for free-text messages (items, quantities, total, name, note).
  - [ ] 4.2 Catalog lookup to compute missing totals.
  - [ ] 4.3 Idempotent ingestion by external id; UNPARSED fallback.
  - _Requirements: 1, 8_

- [ ] 5. Payment extraction
  - [ ] 5.1 Template engine (named groups, mapping, date format).
  - [ ] 5.2 Two synthetic provider templates with examples.
  - [ ] 5.3 Duplicate and UNVERIFIED detection.
  - _Requirements: 2, 8_

- [ ] 6. Matching engine
  - [ ] 6.1 Scoring with reasons; thresholds as configuration.
  - [ ] 6.2 Auto, suggested, partial, over-payment flows with ledger postings.
  - [ ] 6.3 Overrides: confirm, reject, manual match, unmatch with audit log.
  - [ ] 6.4 Property-based tests: idempotency, never over-assign amounts.
  - _Requirements: 3, 4_

- [ ] 7. Suspicion rules
  - One class per rule, one table of examples per rule, plain-language reasons.
  - _Requirements: 5_

- [ ] 8. Board and summary UI
  - Thymeleaf plus HTMX, phone-first layout, five board columns, daily summary, CSV export.
  - _Requirements: 6, 4.5_

- [ ] 9. Simulators and demo tenant
  - Chat simulator, confirmation feed, seven one-click scenarios, seeded synthetic data, scheduled reset.
  - _Requirements: 7_

- [ ] 10. Optional AI extractor
  - Interface, timeout, schema validation, amount cross-check; off by default.
  - _Requirements: 8_

- [ ] 11. Signed machine API
  - HMAC verification, idempotency keys, OpenAPI description.
  - _Requirements: 9.3_

- [ ] 12. Polish and accessibility
  - Visual design pass, keyboard use, contrast, French string scaffolding.
  - _Requirements: 6.3, 10_

- [ ] 13. Deployment
  - Docker image, free container host, health check, demo credentials documented, warm-up note.

- [ ] 14. Submission material
  - Under-3-minute video, write-up of how Kiro was used, README, blog post, social post.

## Kiro workflow to document as you go

Keep short notes in `docs/kiro-notes.md` for the write-up the rules require:

- Spec-driven: how these three files changed during the build, and what the spec caught.
- Steering: which steering lines changed the output the most.
- Hooks: suggested set
  - on save of `*.java`: run the module's unit tests;
  - on change of `requirements.md`: list tasks that now lack a requirement link;
  - before commit: secret scan and license header check.
- MCP: any server used (for example the database or documentation lookups) and what it saved.
- Property-based testing: the bugs it found that example tests missed.
- Vibe coding versus spec: one honest comparison from a real task.
