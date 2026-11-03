# Kiro build notes

Running log of how Kiro, the spec, and the steering files shaped this project.
Updated as each task is completed.

---

## Task 1 — Project skeleton

### How the spec and steering changed the output

#### Spec (`requirements.md`, `design.md`, `tasks.md`)

- **`ingestion` module added from `design.md`.**
  The task list only named five modules (`ledger-core`, `extraction`, `matching`,
  `suspicion`, `web`). The design file's module table added `ingestion` as a
  distinct responsibility (endpoints, HMAC, idempotency). Without reading
  `design.md` first, `ingestion` would have been merged into `web`, which would
  have violated the single-responsibility principle stated in the tech steering.

- **Integer minor units enforced in the data model.**
  Requirement 4.4 says "store amounts as integers in minor units and SHALL NOT use
  floating point for money." This led to every SQL `amount` column being `BIGINT`
  (not `NUMERIC` or `DECIMAL`) and every Java amount field being `long` or `Long`.

- **`UNPARSED` and `NEEDS_REVIEW` statuses in the schema.**
  Requirements 1.5 and 2.3 require that unrecognised messages are stored rather
  than dropped. The `CHECK` constraint on `orders.status` and `payment.status`
  was written directly from those requirement enumerations.

- **Idempotency key in `IngestionRequest`.**
  Requirement 1.3 ("submitting the same external id twice produces exactly one
  order") drove the `UNIQUE (tenant_id, external_id)` constraint on `orders` and
  the `IngestionRequest` value-object validation.

- **Ledger append-only: partial protection in task 1, completed in task 2.3.**
  Requirement 4.2 drove `V007__restrict_ledger_entry.sql`, which revokes `UPDATE`
  and `DELETE` on `ledger_entry`. However, `REVOKE … FROM current_user` only
  removes the privilege from the role that ran the migration — typically the
  database owner — which can trivially re-grant it to itself. This is not a
  reliable guard on its own. Task 2.3 must close the gap with two measures:
  (1) a `BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_entry` trigger that raises
  an exception unconditionally, catching any session regardless of role; and
  (2) separating the migration role (DDL rights, runs Flyway) from the application
  role (INSERT/SELECT only on `ledger_entry`, no DDL). Until task 2.3 is done
  the immutability constraint is advisory, not enforced.

#### Steering (`tech.md`, `security.md`, `product.md`)

- **`ledger-core` has zero web dependencies** — explicitly required by `tech.md`
  ("One module per responsibility; `ledger-core` has no web dependency"). The
  `ledger-core/pom.xml` deliberately omits every Spring Boot starter except the
  test one.

- **All versions pinned** — `security.md` ("pin versions, run a vulnerability
  check in CI") drove pinning every dependency version in `<dependencyManagement>`
  rather than relying on Spring Boot's BOM alone. The OWASP plugin is also pinned.

- **Secrets from environment variables only** — `security.md` ("Secrets only from
  environment variables. Never in the repository, logs, or test fixtures.") drove:
  - `application.properties` using `${DB_URL}`, `${DB_USER}`, `${DB_PASSWORD}`,
    `${APP_SECRET}` with no fallback defaults.
  - `.env.example` with placeholder text (not real values).
  - `.env` already in `.gitignore`.
  - The test profile disabling the datasource auto-configuration so CI does not
    need a running PostgreSQL for unit tests.

- **Phone numbers hashed** — `security.md` ("Phone numbers are stored hashed with
  a per-tenant salt, plus the last 4 digits for display") drove the `phone_hash`
  and `phone_last4` columns in `customer`, and the `senderLast4` field in
  `ParsedPayment`. No raw phone number appears in any record type.

- **English only** — `product.md` ("The code, comments, commit messages, docs and
  UI strings are in English") kept every comment, Javadoc, SQL comment, and this
  file in English.

- **Do not add `.kiro` to `.gitignore`** — explicit in `tech.md`; confirmed by
  the user in the task description. The existing `.gitignore` was left unchanged.

- **Small classes, constructor injection, immutable records** — `tech.md` drove
  using Java `record` types for all value objects (`Account`, `LedgerEntry`,
  `LedgerTransaction`, `ParsedOrder`, `ParsedPayment`, `IngestionRequest`,
  `MatchScore`, `SuspicionFlag`) and `sealed interface` hierarchies where
  exhaustive case coverage matters (`ExtractionResult`, `ParsedMessage`).

- **jqwik imported at the module level** — `tech.md` lists jqwik for
  property-based tests. It is added to `ledger-core`, `extraction`, and `matching`
  now so property tests can be added in tasks 2 and 6 without changing the POMs.

---

## Suggested hooks (to implement)

From `tasks.md` → "Kiro workflow to document as you go":

| Hook | Trigger | Action |
|---|---|---|
| Run module unit tests on save | `PostFileSave` on `*.java` | `mvn -pl <module> test` for the owning module |
| Requirement coverage check | `PostFileSave` on `requirements.md` | Script that lists tasks without a requirement link |
| Pre-commit secret scan | `PreToolUse` on commit tools | `git-secrets` or `trufflehog` scan |
| Pre-commit license check | `PreToolUse` on commit tools | Check each `.java` file has the Apache-2.0 header |

---

## MCP servers used

None yet. Candidates for later tasks:
- A PostgreSQL MCP server could let Kiro query the live schema during task 2–5
  instead of reading migration files manually.
- A documentation server could pull Spring Security or Flyway docs on demand.

---

## Property-based testing: plan

jqwik will cover (tasks 2 and 6):
- Any sequence of balanced postings leaves every transaction balanced.
- Applying the same message twice leaves the ledger unchanged (idempotency).
- Matching never assigns more than `min(payment.amount, order.balance)`.
- Reversal followed by re-post returns accounts to the same balances.

Example tests are always written first; property tests are added when the example
tests pass, to find edge cases example tests miss.

---

## Vibe coding vs. spec: first impression

Writing the skeleton from a spec with explicit module responsibilities, security
rules, and data model made every decision faster. The spec answered "where does
this live?" (module table in `design.md`), "what columns?" (data model section),
and "what invariants?" (requirements and security steering). Without the spec, the
natural vibe-coding path would have been one big Spring Boot app with no module
boundaries, floating-point money columns, and secrets hard-coded for "local dev
convenience" — all of which the spec ruled out before a line of code was written.

---

## Spring Boot 3.3.5 → 4.1.1 upgrade (task 1 fix)

### Why the upgrade was done

The OWASP dependency check reported CVSS 9.8 findings against Spring Boot 3.3.5
libraries (spring-core, spring-web, tomcat-embed-core, jackson-databind,
spring-security-*, postgresql). Rather than suppress or accept those findings,
the project was upgraded to Spring Boot 4.1.1, the latest 4.1.x GA release
(confirmed on Maven Central, released 2026-08-21).

### What the major version upgrade broke and how it was fixed

**1. Testcontainers artifact rename (1.x → 2.x)**

Boot 4.1.1 manages Testcontainers 2.0.5. In version 2.x every module artifact
was renamed with a `testcontainers-` prefix:

| Old (1.x) | New (2.x) |
|---|---|
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` |

The `web/pom.xml` test dependencies were updated to the new names.

Additionally, the Boot BOM manages the Testcontainers BOM via a nested BOM
import, which Maven does not propagate to child modules automatically. The root
`pom.xml` re-imports `testcontainers-bom:2.0.5` explicitly so child modules can
omit versions on individual Testcontainers artifacts.

**2. Autoconfiguration package reorganisation**

Spring Boot 4 split `spring-boot-autoconfigure` into technology-specific modules.
Every autoconfiguration class moved package:

| Class | Boot 3 package | Boot 4 package |
|---|---|---|
| `DataSourceAutoConfiguration` | `o.s.b.autoconfigure.jdbc` | `o.s.b.jdbc.autoconfigure` |
| `DataSourceTransactionManagerAutoConfiguration` | `o.s.b.autoconfigure.jdbc` | `o.s.b.jdbc.autoconfigure` |
| `HibernateJpaAutoConfiguration` | `o.s.b.autoconfigure.orm.jpa` | `o.s.b.hibernate.autoconfigure` |
| `FlywayAutoConfiguration` | `o.s.b.autoconfigure.flyway` | `o.s.b.flyway.autoconfigure` |
| `@AutoConfigureMockMvc` | `o.s.b.test.autoconfigure.web.servlet` | `o.s.b.webmvc.test.autoconfigure` |

The `application-test.properties` spring.autoconfigure.exclude list was updated
to the new class names.

**3. `@SpringBootTest.excludeAutoConfiguration` removed**

Boot 4 removed the `excludeAutoConfiguration` attribute from `@SpringBootTest`.
Exclusions must now go in `spring.autoconfigure.exclude` in the test properties
or in a `@OverrideAutoConfiguration` annotation. The smoke test was simplified to
rely on the properties file only.

**4. `spring-boot-starter-web` deprecated → `spring-boot-starter-webmvc`**

Boot 4 deprecated `spring-boot-starter-web` in favour of the explicit
`spring-boot-starter-webmvc` (Spring MVC) starter. Both `web/pom.xml` and
`ingestion/pom.xml` were updated.

A companion test starter `spring-boot-starter-webmvc-test` now provides
`@AutoConfigureMockMvc` (which no longer ships in the generic
`spring-boot-test-autoconfigure` jar). It was added to `web/pom.xml` as a test
dependency.

**5. jqwik version: stayed on 1.9.3**

jqwik 1.10.x intentionally ships a prompt-injection payload in test output
(documented on the project's GitHub releases page). Version 1.9.3 is the latest
safe release in the 1.9 line and is compatible with JUnit Platform 1.11 which
Boot 4.1.1 ships. The version property in the root POM was updated from 1.9.1
to 1.9.3.

**6. Plugin versions aligned with Boot 4.1.1 BOM**

`maven-compiler-plugin` was updated 3.13.0 → 3.15.0 and `maven-surefire-plugin`
3.5.1 → 3.5.6, matching the versions declared in the Boot 4.1.1 BOM.

### angus-activation / CVE-2025-7962

The OWASP check flags `org.eclipse.angus:angus-activation` for CVE-2025-7962
(SMTP injection, CVSS 8.7) via CPE `cpe:/a:eclipse:angus_mail`.

**This is a confirmed false positive.** CVE-2025-7962 affects the angus-mail
SMTP transport layer, not angus-activation (the Jakarta Activation Framework
implementation for file-type detection). The two artifacts share only the Eclipse
Angus project namespace; angus-activation contains no mail transport code and
cannot perform SMTP injection under any circumstances.

Evidence: DependencyCheck GitHub issue #8144 confirms the misattribution; the
false positive was fixed in DC hosted suppressions PR #8310 (merged 2025-12-22).
The suppression entry in `dependency-check-suppressions.xml` closes the gap
locally until the fix propagates to the DC version pinned in this project.

chat-ledger does not send email. Even if the flag were not a false positive, the
vulnerability would not be reachable from this codebase.

The suppression entry carries an expiry of **2027-06-01** and must be
re-evaluated at that date or when either angus-activation or Dependency Check is
next upgraded.
