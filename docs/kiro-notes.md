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
  role (INSERT/SELECT only on `ledger_entry`, no DDL). **Closed in task 2.3**
  (V008, `docker/initdb/01_create_roles.sh`); see the residual-risk note below.

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
(SMTP injection, CVSS 7.5 in the scan) via CPE `cpe:/a:eclipse:angus_mail`.

**This is a false positive.** CVE-2025-7962 describes an SMTP injection in the
Jakarta Mail SMTP transport (`org.eclipse.angus:smtp`), not in angus-activation
(the Jakarta Activation implementation, used for file-type detection). The scanner
matches the two artifacts only because they share the Eclipse Angus project name.
Source: the CVE record on nvd.nist.gov. The suppression is limited to this jar and
this CPE.

chat-ledger does not send email, so even a real finding would not be reachable
from this codebase.

The suppression entry carries an expiry of **2027-06-01** and must be
re-evaluated at that date or when either angus-activation or Dependency Check is
next upgraded.

### Tomcat patched above the Boot BOM, and one analyzer turned off

After the upgrade, the only remaining finding was `tomcat-embed-core` 11.0.24 (CVSS
up to 9.8), the version managed by the Boot 4.1.1 BOM. The `tomcat.version`
property in the root POM now pins Tomcat 11.0.27, and the OWASP check passes.

The Sonatype OSS Index analyzer is disabled in the `security-check` profile: it needs
credentials the project does not use and only produced errors. This is a deliberate,
named trade-off: the NVD data remains the vulnerability source.

### What the CI taught us (for the write-up)

- The vulnerability check failed on the first pull request: the skeleton had been
  generated on an end-of-life Spring Boot line. A scheduled check in CI is what
  caught it, not a human review.
- The NVD API key must be wired into the plugin configuration explicitly; setting
  the environment variable alone is not enough.
- A first run without a cache downloads the whole NVD feed (about 418,000 records),
  so the data directory is cached between runs.

---

## Task 2.3 - Ledger immutability enforced by the database

### What was added

- `V008`: one plain trigger function `ledger_immutable()` (no `SECURITY DEFINER`)
  attached to `ledger_entry` and `ledger_txn` as a row trigger
  (`BEFORE UPDATE OR DELETE`) and a statement trigger (`BEFORE TRUNCATE`).
  Grants: `chatledger_app` gets `SELECT, INSERT` on the ledger tables and
  `SELECT` elsewhere. There are deliberately no default privileges: each new
  table or sequence must be granted to `chatledger_app` explicitly in the
  migration that creates it, so the app role never gets access to a table
  nobody reviewed. The schema has no sequences today (UUID keys).
- `docker/initdb/01_create_roles.sh`: creates the non-superuser schema owner
  `chatledger_migrate`, the `chatledger_app` group role and the app login, all
  from environment variables. No password is in any migration.
- Three distinct identities: the bootstrap superuser (`POSTGRES_USER`, cluster
  init only), `chatledger_migrate` (Flyway only, via `spring.flyway.user` and
  `spring.flyway.password`, no defaults), and the app login (`DB_USER`).
- `LedgerEntryImmutabilityIT` (Testcontainers PostgreSQL, run by Failsafe in
  `mvn verify`, which now needs Docker).

### Honest limit

The triggers do not apply "whatever the role". The table owner and any
superuser can run `ALTER TABLE ... DISABLE TRIGGER` or `DROP TRIGGER` and then
mutate rows. The trigger does stop the owner from a plain UPDATE, DELETE or
TRUNCATE, but not from deliberately removing the trigger first.

Compensation, not elimination:

- The application connects as a role that is neither owner nor superuser, so it
  cannot alter the triggers (tested).
- The owner role `chatledger_migrate` is used only by Flyway.
- The superuser is used only to initialise the cluster and is never given to
  the app or to Flyway.

Not covered: a compromised migration pipeline or a leaked owner or superuser
credential. Mitigations for later: restrict who can run Flyway, and consider
an event trigger or audit logging that records `ALTER TABLE ... DISABLE TRIGGER`.

### Bugs found while reviewing my own first draft

- `NOINHERIT` on the app login would have stopped it receiving the
  `chatledger_app` privileges. Removed.
- V008 tried to `CREATE ROLE` as a role with `NOCREATEROLE`, which fails.
  Role creation moved to the init script.
- The V009 placeholder migration and the unused `app.db_app_login` block were
  removed; group membership is granted by the init script.

---

## Boot 4: Flyway never ran at startup (found after task 2.3)

**Symptom:** the app started, logged no Flyway lines and left the database empty.

**Cause:** Spring Boot 4 moved Flyway auto-configuration out of `flyway-core`
into its own module. With only `flyway-core` and `flyway-database-postgresql`
on the classpath there is no `Flyway` bean and no migration. Nothing failed, so
the Boot 4 upgrade build stayed green. The earlier `LedgerEntryImmutabilityIT`
did not catch it because it calls `Flyway.configure()` by hand.

**Fix:** `web/pom.xml` now uses `spring-boot-starter-flyway` plus
`flyway-database-postgresql`. The artifact name comes from the Spring Boot
reference (How-to > Database Initialization > Flyway).

**Guard:** `FlywayStartupIT` boots the real Spring context against a
Testcontainers PostgreSQL. Flyway runs as the migrate role, the datasource uses
the app role, and the test asserts the four V008 triggers exist. It was run
against the old dependencies first and failed with `No qualifying bean of type
org.flywaydb.core.Flyway`, so it does detect the regression.

**Lesson:** a green build after a major upgrade says nothing about
auto-configuration that silently disappeared. Test the startup path, not a
re-implementation of it.

---

## Tasks 2.1 and 2.2 - Ledger core and property-based tests

### What the spec caught

- **"The sum of all balances is zero" is only true for one of two numbers.**
  The task asked for balances "signed by account type" and for tenant balances
  that sum to zero. Both cannot hold: signed by type, a sale shows +5000 on the
  asset and +5000 on revenue. Only debits minus credits sums to zero. The model
  now has both: `netDebitMinor` (sums to zero) and `signedMinor()` (reads
  naturally). Found while writing `AccountBalance`, before any test existed.
- **Requirement 4.2 plus the task 2.3 triggers shaped the whole design.** A
  correction must be a new transaction, so reversal needed a link column. And
  because `ledger_entry` can no longer be updated, a tenant column on entries
  could not be back-filled, which ruled out the obvious fix for tenant
  consistency (see below).
- **A header with no entries slips past an entry-level trigger.** The deferred
  balance trigger on `ledger_entry` never fires for a transaction that has no
  entries. It needed a second one on `ledger_txn`. The test
  `header_without_entries_is_refused_by_the_database` exists because of this.
- **"Never floating point" does not mean "never overflow".** A `long` wraps
  silently. Per-side totals use `Math.addExact`. Account balances are limited to
  [-Long.MAX_VALUE, Long.MAX_VALUE] because `Long.MIN_VALUE` cannot be negated.

### What I changed from the spec / the request

- **"Check that the reversal has the same tenant" is a composite foreign key,
  not a CHECK.** A CHECK cannot read another row. V009 adds
  `UNIQUE (id, tenant_id)` and `FOREIGN KEY (reverses_txn_id, tenant_id)`.
- **Entry/account tenant consistency is a BEFORE INSERT trigger, not a
  constraint.** `ledger_entry` has no tenant column. I also made
  `account.tenant_id` immutable, which was not asked for, because otherwise the
  check is hollow: an account could be moved after the fact.
- **`isReversed` was added to the repository port.** Without it a second
  reversal of a transaction whose reversal would overflow was reported as an
  overflow instead of "already reversed".
- **Two existing tests broke, correctly.** `LedgerEntryImmutabilityIT` seeded
  single, unbalanced entries in autocommit mode. V009 refuses that, so the
  fixtures now write balanced pairs in explicit database transactions, and
  `FlywayStartupIT` lists the three new triggers.
- **The persistence classes are plain JDBC, not Spring beans.** Nothing in `web`
  uses them yet, and the smoke test context has no datasource. Wiring comes with
  the first consumer (task 6).
- **The PostgreSQL property reuses the in-memory one** through a `ledger-core`
  test-jar (generators, scenario runner, in-memory repository) instead of
  copying them.
- **An earlier comment in the root pom was wrong.** It said Boot 4.1.1 ships
  JUnit 5.11. It ships JUnit 6. jqwik 1.9.3 was checked to run on it and was kept
  as asked.

### Known gaps (not enforced by the database)

- A reversal must mirror its original, and a reversal cannot be reversed. The
  database checks only that a reversal is balanced, in the same tenant, and
  unique. A buggy writer could record an unrelated transaction as the "reversal"
  of another and block its real reversal.
- The overflow check in `LedgerService` reads the balance and then writes. Two
  concurrent postings to the same account could both pass it. The database does
  not enforce the balance range, so the result would be a loud `LedgerException`
  when balances are read, not a silent wrap-around. A fix needs a lock or a
  database-side check.
- Deferred triggers, like the V008 ones, can still be dropped by the table owner
  or a superuser (see task 2.3).

### One honest remark on what worked and what did not

**What worked:** an independent oracle. The scenario runner keeps its own
BigInteger balances and, for every operation, predicts whether the ledger must
accept or reject it. That makes the properties non-vacuous: code that rejects
everything fails as surely as code that wraps around. **Mutation testing earned
its keep:** the first version of the properties passed even when I replaced
`Math.addExact` with `+`. Random entry lists almost never produce two sides that
are equal only after wrapping. Only my example test caught it. I added a
generator that builds exactly that case, and the mutation is now caught by two
properties. The same check on the database (removing the balance triggers, then
the reversal unique constraint) fails exactly the matching tests.

**What did not work:**
- While cleaning up after a mutation I ran `git checkout -- .` for the whole
  repository. It reverted my own uncommitted edits to five tracked files
  (`Account`, `AccountType`, `LedgerEntry`, `LedgerTransaction`, the
  `ledger-core` pom) and brought the old smoke test back. Nothing of anyone
  else's was lost (the tree was clean when I started) and I restored it from a
  backup and by rewriting, but it was a careless command. Mutate a copy and
  restore with `cp`, never with a repository-wide git command.
- Docker Desktop had stopped mid-session, so every Testcontainers test errored
  until I restarted it. That was the environment, not the code.
- My first database mutation run printed nothing: `-pl web` could not resolve
  the new test-jar without `-am`. I noticed only because the output was empty.
  Always check that a negative check actually ran.

---

## Task 3, checkpoint 1 - tenant integrity, row-level security, isolation guards (3.1 to 3.3)

Authentication, sessions, CSRF, headers and limits (3.4 to 3.10) are not built yet; this section is
only about the first checkpoint.

### What the spec and the design review caught

- **The commit-time assumption was tested first, as asked, and holds.** The design relied on a
  deferred constraint trigger still seeing the transaction-local tenant setting at `COMMIT`.
  `CommitTimeTenantSettingIT` proves it on PostgreSQL 16.4 with a scratch schema, and a control
  case (blank the setting just before commit, the trigger then sees zero rows) shows RLS really
  applies inside the trigger, so the first test is not passing by accident. It had no effect on
  the plan because it passed, but the plan depended on it.
- **V009's tenant trigger would have failed open under RLS.** Found by reading V009 against the
  RLS rules, before any code. Another tenant's account is invisible to the trigger, which then
  stepped aside, and foreign-key checks bypass RLS, so a cross-tenant ledger entry would have
  committed. V011 replaces the function so that "cannot see it" is a violation.
- **Foreign keys cross tenants.** PostgreSQL documents that foreign-key and unique checks bypass
  row security. Today a `match` could link tenant A's order to tenant B's payment. V010 replaces
  the single-column keys with composite ones that include `tenant_id`.
- **Requirement 9.6 conflicted with 4.2.** Resolved by you: deleting personal data, ledger kept.
  Requirement 9.6 was rewritten, and the retention rule is recorded in `design.md` as an
  assumption to check against local accounting law.
- **The existing tests broke in useful ways.** Under RLS every raw-SQL helper needed a tenant, and
  every tenant fixture needed a `slug`. That is the policies doing their job.

### What changed from the design while building

- The isolation guard is a pure function over a catalog snapshot (`IsolationGuard`), not a single
  test. That lets the same rules run on the real database and, in `IsolationGuardTest`, on
  invented catalogs built to fail, so a green result means something.
- The registry covers every table in `public`, not only tables with a `tenant_id` column. A table
  must be registered or listed as holding no tenant data (only `flyway_schema_history`).
- The per-table behaviour test runs as a probe role that holds full DML privileges and is a member
  of `chatledger_app`. The real application role lacks most privileges, so testing with it alone
  would pass because of missing privileges and prove nothing about the policies.
- `tenant` has a read-only policy, `ledger_entry` follows `ledger_txn`, and `audit_log` is
  insert-and-select only for the application role.
- `JdbcLedgerRepository` now runs every call in a `TenantScope`, and `balances()` inlines its SQL
  literal so the build can check nothing is string-built.
- `LedgerDatabase` credentials are random per run (part of the throwaway-password debt; the
  remaining test classes are handled in 3.6).

### Mutation checks (what the guards actually catch)

| Mutation | Caught by |
|---|---|
| RLS not enabled on `flag`; `orders` policy `USING (true)` | catalog guard, per-table behaviour test, no-tenant test |
| V009's fail-open trigger restored | `an_entry_on_another_tenants_account_is_refused_because_it_is_invisible` only |
| Single-column foreign key on `order_line` | catalog guard (foreign-key rule), `TenancyIntegrityIT` |
| A new tenant table with no registry entry | catalog guard, naming the table |

The fail-open case matters: the generic per-table harness did **not** catch it, because the entry's
own policy happens to block that particular insert. Only the dedicated test, which puts tenant A's
transaction together with tenant B's account, does. The generic harness is necessary but not
sufficient; hazards found by reading still need their own tests.

### Defenses weakened or traded off so far (from the design's register)

1. The tenant setting can be set by any SQL the application role runs, so RLS protects against our
   own bugs and not against SQL injection. Compensation: prepared statements only (a build check),
   least privilege, application scoping as the primary control.
2. The owner bypasses RLS (`ENABLE`, not `FORCE`). `RowLevelSecurityIT` documents it. Compensation:
   the application role is not owner, superuser or `BYPASSRLS`, holds no `TRUNCATE`, `REFERENCES`
   or `TRIGGER`, and the owner is used by Flyway only.
12. Test credentials: partly paid down (`LedgerDatabase`); the rest in 3.6.
13. The task 2.3 triggers can still be dropped by the owner.

Limits of the new checks, so nobody over-trusts them: the "no string-built SQL" check is a
heuristic scan of `prepareStatement` arguments, not a parser; the controller scan cannot see inside
an untyped `Map` request body; repositories are found by the `*Repository` class name.

### One honest remark

What worked: writing the riskiest assumption as a test first, with a control, and then running the
mutations before trusting the green. What did not: several tests passed or failed for the wrong
reason on the first try (the repository scan picked up a test fake, fixtures lacked a `slug`, a
generics ambiguity in my own assertions), and the one real hole, the fail-open trigger, slipped past
the clever generic harness. The lesson is the one from task 2: a guard you have not seen fail is a
guess.

## Task 3, checkpoint 2a - authentication, sessions, CSRF, provisioning (3.4 to 3.7, part of 3.8)

Stopped here on purpose, as asked: rate limits, security headers and CSP, template lint, error pages
and size limits (rest of 3.8, 3.9, 3.10) are not built.

### What the spec and the design review caught

- **A malformed stored hash would have been a 500, not a failed login.** Found by the first run of
  `PasswordHashingTest`: Argon2 throws `IllegalStateException` for a damaged encoding and the verifier
  only caught `IllegalArgumentException`. It now answers "no match" for both, with a test.
- **The framework logs the session id.** `SecurityContextLogoutHandler` writes "Invalidated session
  <id>" at DEBUG. The log-capture test runs the whole app at DEBUG on purpose and failed on it. Logout
  now uses a small handler of our own that does not log it.
- **The framework's cookie deletion is ignored by browsers for a `__Host-` cookie** (it omits `Secure`).
  The same handler sends the deletion with the flags the cookie was issued with.
- **JDK 22 and later break piped passwords.** `System.console()` is non-null even when stdin is piped,
  so `echo ... | provision-user` would have prompted into the void. Maven here runs on JDK 23, which is
  how a test that was meant to read stdin came back "skipped". Fixed with `Console.isTerminal()`.
- **CI held fixed passwords.** The new secret scan flagged `ci_password` and `ci-secret-...` in
  `ci.yml` (the init script's `${VAR:?}` guards were false positives and the scanner was tightened).
  CI now generates masked random values per run.
- **Requirement 9.1 needed more than the URL rules.** The handler coverage test fails the build for any
  request handler with neither `@PreAuthorize` nor a place on a four-name public list.
- Three integration tests still held hard-coded throwaway passwords and created the migrate role with
  `CREATEDB`, unlike production. Both fixed.

### What changed from the design while building

- Login is a controller, not Spring's form login, so the whole sign-in is one readable method.
- Migration numbers: the authentication plumbing is V013 (V012 became `audit_log` immutability, which you
  asked for in this checkpoint).
- The application role has no INSERT or UPDATE on `app_user` or `tenant`. A user cannot change their own
  role, and the running application cannot create a user at all.
- Failed sign-ins are logged (keyed hash, client address) but not written to `audit_log`; successful
  sign-in is written first and, if the write fails, no session is created (tested). Sign-out never blocks.

### Security check, as requested (reported before any suppression; none written)

`-Psecurity-check` (plugin 10.0.4, NVD data from 2026-11-07, run on a copy of the tree): 58 dependencies,
0 vulnerability entries, nothing suppressed. `bcprov-jdk18on` 1.86: 0 findings. A control run with
log4j-core 2.14.1 planted failed the build at the CVSS 7 gate, so the scanner does work. A cross-check on
OSV.dev (name and version only) shows no advisories for 1.86; twelve older ones are fixed in 1.85 or
earlier, the newest GHSA-qp49-qgx5-5m26 and GHSA-9pwp-9qqc-pr26 (fixed in 1.85). No NVD API key was set, so
the scan ran with the cached feed and a warning. It must be re-run at the end of task 3 if a dependency is
added (the rate limiter may add Caffeine).

### Mutation checks

| Mutation | Caught by |
|---|---|
| `/admin/**` open to any signed-in user | `AuthorizationIT` (1 failure) |
| CSRF disabled | `AuthenticationIT` (2 failures, 14 errors) |
| Password check skipped for an unknown workspace | `LoginAuthenticationProviderTest` (2 failures) |
| User status re-check never due | `SessionLifetimeIT` (5 failures) |
| Absolute timeout never applied | `SessionLifetimeIT` (1 failure) |
| Verifier catching only `IllegalArgumentException` | `PasswordVerifierTest` (found, not planted) |

Not mutation-checked: the audit-log immutability triggers beyond their own tests, the provisioning
privilege tests, the secret and sign-up scanners (each was shown to catch invented input instead).

### Defenses weakened or traded off in this checkpoint

1. No lockout and no rate limit yet (3.9). Until then, only the cap of four concurrent Argon2 checks
   (waiting 2 seconds, then 429) limits guessing; that is a denial-of-service guard, not a guessing
   defense. Do not expose this build.
2. `SameSite=Strict` means a link from another site opens the app signed out until the next click. Chosen
   by you; the cost is one extra click.
3. The client address is logged on failed sign-in. It is personal data; kept for following up attacks.
4. Java cannot wipe a `String`, so the typed password stays in memory until garbage collection in the
   provisioning command and the login request. Accepted; not reachable without access to the process.
5. Clear-Site-Data is only sent on secure requests (so it was tested with a secure mock request, not a
   real TLS connection).
6. The stored-hash format check is `NOT VALID`, so rows that predate it are not re-checked. There are
   none created by the application.
7. Retention of the ledger after personal data is deleted is still an assumption to check against local
   accounting law.

### Not verified

The provisioning commands were tested through their class with a real PostgreSQL, but not by running
`java -jar` against a built jar. The cookie flags were checked on a real Tomcat over HTTP, not over TLS.
Timing equality of failed logins is argued from identical work (a unit test counts the calls), not
measured.

### One honest remark

The log-capture test and the unit test with a mock stdin both looked like they proved something and did
not until the harness was made to bite: the first only found the session id because I turned DEBUG on, and
the second was skipped on this machine, which is what exposed the JDK 22 console change. Asking "did the
test actually run, and at what level" caught more than any of the clever tests did.

## Task 3, checkpoint 2b - headers, CSP, error pages, limits (rest of 3.8, 3.9, 3.10)

### What the spec and the design review caught

- **A limit on `Content-Length` is not a size limit.** The design said "counts streamed bytes", and the tests
  that send the same oversized body with and without a declared length show why: the JDK client sends it
  chunked and a header check alone passes it. The guard reads at most the limit and hands the checked bytes
  on, so the CSRF filter and the controllers see what was checked. A valid sign-in sent chunked is a test too,
  because replacing the request is exactly the kind of change that quietly breaks form parsing.
- **Servers repair bad bytes; the design said reject.** Tomcat turns invalid UTF-8 into U+FFFD and drops
  broken percent sequences. The guard parses forms, queries and JSON strictly (`InputRules`) and answers 400.
  It also means a NUL in the login form is a 400, not the uniform "sign-in failed", so the old "garbage
  characters" case moved out of the uniform-failure test and into its own.
- **The framework puts the request path and a timestamp in error responses regardless of
  `server.error.include-path=never`.** Found by an error-page test that looked for them. `MinimalErrorAttributes`
  now allows only status and reason.
- **Template comments were being sent to every client**, including my notes about the CSP and the error pages.
  Found by reading a failed assertion's output. They are now Thymeleaf parser comments, removed at render, and a
  test fails if a rendered page contains `<!--`.
- **Rate limits are counted before the CSRF check on purpose.** A test posts without a token ten times (each is a 403)
  and shows the eleventh is a 429; otherwise leaving the token out would give unlimited attempts.
- **The test suite itself needed the limits.** Every IT signs in from one address, so a shared context would trip
  the real per-address limit. The base class sets them very high; the limit tests run in their own contexts with
  the real defaults.
- Two of my first drafts were wrong in ways the tests exposed: the percent-sequence bounds check in
  `InputRules.decode` (a muddled condition, rewritten and covered by `%`, `%4`, `%zz` cases) and a lambda capturing
  a non-final variable in the guard.

### What changed from the design while building

- **Ingestion limit has no real endpoint yet.** It is a named limit on `RateLimits` keyed by the tenant from the
  principal, proven end to end through a test-only probe endpoint (and unit-tested). Task 4 and task 11 must call
  `RateLimits.ingestion(tenantId)`; I did not invent an ingestion URL to hang it on.
- The per-account limit is applied in the login controller (where the submitted account is known), the per-address
  limits in a filter in front of CSRF.
- Limits are written `limit/period[/burst]` (for example `60/1m/30`) and parsed at startup, so a bad value stops the
  application instead of disabling a limit.
- `Cache-Control` stays the framework's `no-store` everywhere; versioned static files, which should be cacheable,
  arrive with task 8 and are now a register item (17).
- The layout is a `head` fragment plus an `hx-headers` attribute on `<body>` of every page under `app/`, enforced by
  a lint (a page there without it fails). The htmx library is not added yet (task 8).

### Security check (Caffeine added)

`-Psecurity-check` re-run on a copy of the tree: 60 dependencies analysed, 0 vulnerability entries, nothing
suppressed; `caffeine-3.2.4.jar` is in the report with no findings. The NVD data was the cached copy from earlier
today (the plugin skipped its update), so the result is as current as that feed. No NVD key was set.

### Mutation checks

| Mutation | Caught by |
|---|---|
| CSP header removed | `SecurityHeadersIT` (2 failures) |
| Chunked bodies not read or counted | `RequestGuardIT` (3 failures) |
| Per-account limit disabled | `RateLimitsIT` (4 failures) |
| Token bucket refilled past the burst | `InMemoryRateLimiterTest` (1 failure) |
| Inline `style=` added to a page | `TemplateLintTest` (1 failure) |
| Error attributes back to the framework's | `ErrorPagesIT` (1 failure) |
| Control characters allowed in input | `InputRulesTest` and `RequestGuardIT` (4 failures) |
| Per-address filter removed from the chain | `AddressLimitsIT` (4 failures) |

### Defenses weakened or traded off in this step (design register 14 to 18)

1. The per-account limit lets a stranger who knows a workspace code and email lock that user out for up to 15 minutes
   (tokens come back one per 3 minutes; a successful sign-in resets it). The alternative is unthrottled guessing.
2. Limits count the socket address and ignore `X-Forwarded-For`; behind a proxy all users share one address until
   deployment (task 13) names the proxy. A test proves a forged header does not choose the key.
3. Bodies are held in memory up to their cap (4 KiB login, 16 KiB JSON, 64 KiB otherwise).
4. Anything with a control character or bad encoding is refused, which could refuse a legitimate odd client.
5. Single node: counters reset on restart and multiply by the number of nodes; the 100,000-key cap lets an attacker
   with that many addresses reset a bucket.
6. HSTS was tested with a secure mock request, not a real TLS connection, and is off behind a proxy until task 13.

### Not verified

- The oversized-header test accepts 400 or 431 or a dropped connection, because I did not pin down which this Tomcat
  sends; it proves the request is refused, not how.
- Request bodies larger than the container's swallow limit were not tested (the client would be reset while writing).
- Real TLS, real proxies, and many concurrent clients were not tested; limits were checked with one client at a time.
- Memory use under the 100,000-key cap was checked by key count in a unit test, not measured in bytes.

### One honest remark

The mistake worth keeping: I wrote the error-page tests against what I assumed a browser gets, and they failed
because my test client sent no `Accept` header and the framework answered with JSON. That was the test's fault, but
fixing it led to two real findings (the `path` and the comments). A failing test that is "my fault" is still worth
reading to the end.

## Correction: a unit test that needed Docker

**Green on a machine with Docker is not the same as green everywhere, and our own test mixed the two.**
`SourceRulesTest.every_repository_class_is_registered_with_a_cross_tenant_test` is a unit test, but it checked
that each registered contract test exists with `Class.forName(name)`, which also *initialises* the class. The
named class is an `*IT` whose static initialiser starts a Testcontainers PostgreSQL, so the unit phase failed
(`ExceptionInInitializerError: Could not find a valid Docker environment`) on any machine without Docker. I only
ever ran it with Docker up, so I never saw it. It now calls
`Class.forName(name, false, SourceRulesTest.class.getClassLoader())`, which only checks that the method exists.

How it was checked, with Docker Desktop actually stopped (`docker info` could not connect, socket gone):
`mvn -pl web -am test` passed, 106 unit tests in `web` and all modules, no failures or skips. With the old
`Class.forName(name)` put back, the same run failed with exactly that error, so the fix is what made it pass.
No other unit test touches `LedgerDatabase`, `Testcontainers` or an `*IT` class (only this one referred to one,
and only by name). Two other tests call `Class.forName` on controller classes, which have no static state.

Two things this still does not cover: nothing stops a future unit test from importing `LedgerDatabase`, so the
no-Docker run is now the check, and it is worth doing in CI as a separate step. And `mvn -pl web test` on its
own (without `-am`) cannot resolve the `ledger-core` tests jar on a fresh checkout; use `-pl web -am`.
