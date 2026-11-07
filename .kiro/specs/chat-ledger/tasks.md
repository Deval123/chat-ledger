# Tasks: chat-ledger

Work top to bottom. Each task ends with passing tests. Reference the requirement in the commit message.

- [ ] 1. Project skeleton
  - Maven multi-module (`ledger-core`, `extraction`, `matching`, `suspicion`, `web`), Java 21, Spring Boot, Flyway, PostgreSQL via Docker Compose.
  - Public repo files: LICENSE (Apache-2.0), README (English), `.kiro` committed and not ignored.
  - _Requirements: 4, 9_

- [x] 2. Ledger core
  - [x] 2.1 Accounts, transactions, entries, balance invariant, reversals.
  - [x] 2.2 Property-based tests: balanced transactions, reversal returns to the same balances.
  - [x] 2.3 Database constraints: no update or delete on entries.
  - _Requirements: 4_

- [x] 3. Tenancy and security base
  Design: `design.md`, section "Tenancy and authentication". Rules for every step: never edit
  V001-V009; schema changes go in new migrations with explicit grants, no default privileges, and
  the application role owns no table; no password, hash, token or session id in any log; generic
  login errors; `mvn clean verify` green before a step is ticked. A step is done only when every
  "Verify" line below is a passing test (or a recorded command output, where stated).
  - [x] 3.0 Design approved by the owner, with these answers: 9.6 means deleting personal data and
    keeping the ledger (requirement rewritten, retention assumption recorded); HELPER matrix as
    proposed; dedicated provisioner role; an operator `provision-user` command; `SameSite=Strict`;
    30 minutes idle and 12 hours absolute, configurable. Work is in two checkpoints: first 3.1 to 3.3,
    then a stop for review, then 3.4 to 3.10.
  - [x] 3.1 Tenancy integrity (V010): `tenant.slug` and `disabled_at`, lower-case emails, composite
    foreign keys that include `tenant_id`, `tenant_id` on `order_line` and `match`.
    Verify: a customer, order, payment or user from another tenant cannot be referenced (customer
    on an order, order and payment on a match, actor on a flag); a catalog test fails for any foreign
    key between tenant tables that omits `tenant_id`; the migration applies on an empty database and
    on one with rows.
  - [x] 3.2 Row-level security (V011) and `TenantScope`: policies on every tenant table including
    `ledger_entry`; fail-closed replacement of V009's tenant trigger; `JdbcLedgerRepository` and
    existing integration tests moved onto tenant transactions; `audit_log` insert and select only.
    Verify: with no tenant set the application role sees and writes nothing; a one-connection pool
    carries no tenant after a tenant transaction; the deferred balance trigger still refuses an
    unbalanced transaction and accepts a balanced one under RLS (the commit-time assumption is
    proven, not assumed); an entry on another tenant's account is refused under RLS; the
    application role is not owner, superuser or `BYPASSRLS` and holds no `TRUNCATE`, `REFERENCES`
    or `TRIGGER`; owner-seeded data still loads.
  - [x] 3.3 Isolation coverage guard.
    Verify: a catalog test requires RLS and a tenant policy on every table in `public` (allowlist
    `flyway_schema_history`); a registry test fails, naming the table, when a table with tenant data
    has no registered isolation check (shown failing against a synthetic extra table); the
    behaviour check runs for every registered table (A sees A, B sees B, no scope sees nothing,
    cross-tenant insert, update of `tenant_id`, update and delete all refused or empty); every
    `*Repository` class has a cross-tenant contract test; a new `SECURITY DEFINER` function fails the
    build until listed; no controller parameter or request field is named like a tenant; `web` main
    code builds no SQL by string concatenation.
  - [x] 3.4 Password hashing and policy: Argon2id through `DelegatingPasswordEncoder`, pinned
    `bcprov-jdk18on`, length 12 to 128, common-password list, at most four verifications at once.
    Verify: stored hashes start with `{argon2id}$argon2id$` and carry parameters at or above 19456 KiB,
    2 passes, 1 lane; no plaintext password is ever stored or logged; correct and wrong passwords
    verify as expected; the policy rejects short, long, email-derived and common passwords;
    `mvn -Psecurity-check verify` is run and every finding is reported with its severity before any
    suppression is written.
  - [x] 3.5 Authentication (V013 `resolve_tenant_for_login`): workspace, email and password login,
    principal built from the database row, disabled users and tenants refused, user status re-read
    at most every 60 seconds.
    Verify: unknown workspace, unknown email, wrong password and disabled account return a byte-
    identical status, redirect and body; the same database work and a dummy verification run when the
    workspace is unknown; the tenant in the principal comes from the row, not the form (a forged
    `tenant` field has no effect); disabling a user or changing a role takes effect within 60
    seconds; a captured log of every path contains no password, hash, session id, token or email.
  - [x] 3.6 Provisioning, no self-registration and no UI: `chatledger_provisioner` role (in
    `docker/initdb` and `.env.example`), `provision_tenant` and `provision_user` (V013), and two
    operator-run one-shot commands, `provision-tenant` and `provision-user`. The password is read from
    a prompt or standard input only, never from the command line or an environment variable.
    Verify: `/register` and `/signup` are not served; `provision-tenant` creates one tenant and one
    SELLER and `provision-user` adds a SELLER or HELPER to an existing tenant, each reading the password
    from standard input; passing a password or hash as an argument or environment variable is not
    accepted; both refuse a duplicate slug or email, an unknown workspace, a missing value, a
    placeholder, a weak password and a bad slug, email or role, changing nothing; neither prints a
    secret; the application role cannot call either function or insert into `tenant` or `app_user`; the
    provisioner role has no table privileges; a scan finds no `INSERT` into `tenant` or `app_user` in
    any migration and no literal password in properties, SQL or scripts; test credentials are random
    per run (the throwaway passwords in the existing integration tests are replaced).
  - [x] 3.7 Authorization: permission table for SELLER and HELPER, method security, deny by default,
    `/admin/**` for SELLER.
    Verify: an exhaustive role-by-permission test against an independent expected table; HELPER gets
    403 on export, delete and user-management endpoints (test endpoints stand in until those features
    exist); unauthenticated requests are redirected to `/login`, and htmx requests get 401 with
    `HX-Redirect` instead of a swapped login page; a test fails for any handler that has neither
    `@PreAuthorize` nor a place on the public allowlist; a user cannot change their own role.
  - [x] 3.8 Session, CSRF, headers and CSP: cookie flags, idle and absolute timeouts, fixation
    protection, `POST /logout`, Thymeleaf and htmx CSRF contract, security headers, template lint,
    clean error pages.
    Verify: `Set-Cookie` has `HttpOnly`, `Secure`, `SameSite` and the `__Host-` prefix in the
    production profile, and startup refuses `Secure` off under `prod`; the session id differs before and
    after login and the old id is rejected; idle and absolute timeouts end the session (injected
    clock); a missing or wrong CSRF token gives 403 on login, logout and an htmx-style POST, and a
    valid header token succeeds; `GET /logout` is not served; every response carries the CSP, nosniff,
    referrer, frame, COOP, CORP and permissions headers, and HSTS only over HTTPS; a template lint
    fails on inline `<script>`, `style=`, `on...=` handlers, `th:utext` and unescaped inline output;
    error pages contain no stack trace, message or framework version.
  - [x] 3.9 Rate and size limits: `RateLimiter` interface with the in-memory implementation, login
    and ingestion filters, body size filter, field validation helpers.
    Verify: the limiter is unit-tested with an injected clock (refill, burst, bounded memory); the
    sixth attempt for one account key and the eleventh from one IP return 429 with `Retry-After`,
    identically for accounts that do not exist; one tenant's ingestion limit does not affect another;
    oversized bodies get 413 for both declared and chunked requests on login and ingestion; invalid
    UTF-8, NUL and control characters are rejected.
  - [x] 3.10 Close-out.
    Verify: `mvn clean verify` green; `mvn -Psecurity-check verify` findings listed with severity and
    any suppression carrying CVE, jar, justification and expiry; `docs/kiro-notes.md` records what the
    spec caught, what changed from the spec and every weakened defense from the design's register;
    README has a short security section.
  - _Requirements: 9 (and 4.3, 4.5, 9.6 for tenant separation and who may export or delete)_

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
