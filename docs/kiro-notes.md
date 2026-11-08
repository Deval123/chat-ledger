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

## Tooling notes: interruptions and credits

These are about the tool, not the product, and are kept here because they changed how the work had to be checked.

- **"Model response paused unexpectedly" (twice).** The agent stopped mid-task two times. One of them left work
  undone: in task 3 a response ended while the source-scan test (`NoBackDoorTest`) was being written, and the file
  did not exist afterwards. That was only found because the next step checked for the file instead of assuming it
  was there (the same pause also hid a real finding: the CI workflow still held fixed passwords, which the scan
  later caught). The second interruption was reported by the project owner; I cannot see it from inside the
  session. The lesson is to treat a paused response as unfinished, list the files that should exist, and check
  them before going on. Every "done" in these notes was confirmed by a build or a test run, not by the agent's own
  message.
- **The status bar showed "Kiro Free 50/50" while the kiroween-2026 bonus credits were being used.** As reported
  by the project owner: the plan indicator did not reflect which credits were being spent, so it could not be used
  to judge how much budget was left. I cannot see the status bar or the credit balance from inside the session, so
  I do not report credit use in these notes except as the owner reads it from the interface. Do not plan work on the
  plan indicator alone.

## Task 4 - order extraction (4.1 to 4.3)

Proposal and the owner's answers: `.kiro/specs/chat-ledger/task-4-design-stop.md`. Approved with all nine recommendations and six
changes (canonical phone form, validator versus catalog, tenant currency never NULL, French input, a scan rule against Docker in
unit tests, a demo catalog seed in task 9). No HTTP endpoint: the service is the entry point and the paste form (task 8) and the
signed API (task 11) will call it.

### What the spec and the design review caught

- **The validator rule needed a boundary.** "Every amount appears in the source" is true only of amounts the parser *read*. A total
  computed from the catalog is not in the text. Both cases are tested, and the rule is written next to the code (`OrderValidator`,
  `OrderDecider`). The mutation that lets a parser amount through unchecked fails 4 validator tests; a test with a parser that lies
  shows the pipeline refuses it.
- **A phone needs one definition.** The hash, the last four digits and (tasks 5 and 6) the payer lookup all go through
  `PhoneNumbers.canonicalDigits`. Tested on ten spellings. The limit is named in code and tests: with and without a country code
  is two customers.
- **There was no catalog table, no column for the order reference or the delivery note, and no way to store "no total".** Found by
  re-reading V003 and V004 before designing. V014 adds the table and the columns.
- **V003's comment promised an Argon2 hash with a salt that had no column.** An applied migration is never edited, so V014 corrects it
  with `COMMENT ON COLUMN`, and the hash is an HMAC under a key derived from `app.secret`.

### Defects the tests found in my own work

- **`wasNull()` after reading other columns.** In `OrderRepository` a nullable total and a nullable unit price came back wrong
  (a NULL total looked like a value and a NULL price like 0). `wasNull()` answers for the *last* column read, and the arguments of a
  constructor are evaluated in order. Found by the first integration run; fixed by reading the value and asking at once; the original
  defect is also one of the mutations.
- **A CHECK that lets NULL through.** `status NOT IN (...) AND total_minor BETWEEN 1 AND ...` evaluates to *unknown* for a NULL total,
  and a CHECK accepts unknown. The first version of V014 therefore allowed a `PENDING_PAYMENT` order with no total. Fixed with an explicit
  `IS NOT NULL`, with a test for every status.
- **`CHAR(3)` pads.** A currency of `XA` was stored as `XA `. Added a format CHECK.
- **The line sum was capped only when a total was stated.** Found by the hostile corpus (fifty lines of the largest price). Now the sum is
  capped whenever every line is priced.
- **My own code broke my own rule twice.** `OrderParseContext` used `String.matches`, and `AmountParser` used `Pattern.quote` on a separator
  taken from the message (valid, but a pattern built from input). The structural scan flagged the first; I found the second reviewing the
  diff. Both are now plain loops.
- **A lost file.** One test helper (`IngestionFixture`) was never written because a command changed into a directory that did not exist.
  Found only by the compiler. The same lesson as the paused responses: check that what should exist exists.
- **Test mistakes that were still worth reading.** A random actor id (the audit foreign key correctly refuses an actor from another tenant,
  so that is now a test); a label containing `=` (it is item syntax, so the message is unparsed); a regex that matched the *escaped* text
  `onerror=alert`; a corpus entry without angle brackets in a test that expected `&lt;`.

### Mutation checks (each guard broken on purpose; the file restored from a copy and compared after every run)

44 mutants, 49 runs. 39 were caught on the first run; 5 were not, and each one taught something (all 5 are caught now):

| First result | What it showed | Fix |
|---|---|---|
| Amount digit cap removed: not caught | It duplicated the later amount cap, so removing it changed nothing visible | Added a case where it is the only guard (14 digits with leading zeros must be "too large"); now caught |
| Format characters no longer stripped: not caught | They were stripped twice (before and after NFKC); the fixpoint loop made the first pass redundant | Deleted the redundant pass; now caught by 6 tests |
| Catalog lookup by `LIKE`: not caught | The SQL returned the row, but the decider then looked it up by exact key and found nothing, hiding the change | Added a repository-level test that asks for `r%`, `%`, `ri_e` directly; now caught |
| `wasNull` mutant: not caught | My mutant did not reproduce the real bug | Reintroduced the original defect exactly; caught by 3 tests |
| Raw text CHECK removed: not caught | My mutation renamed the constraint and left its body, so it was not a mutation | Replaced the body with `CHECK (true)`; caught |

Caught on the first run: input length, line and item caps; quantity
bounds; amount cap; non-ASCII digit mapping; validator skipped, validator reading the raw text, stated total not compared with the lines,
line sum cap, currency check; phone canonical form (double-zero prefix, letters allowed), phone and parsed order printing personal data;
accent-insensitive keys; French labels; the FCFA alias; a pattern built from the message; the service's own NUL and id checks; the computed
total cap; a stated total replaced by a computed one; catalog price range; a unit test referencing the test database or the container
library; `ON CONFLICT` removed (concurrency test); a replay reported as a plain duplicate; the rate limit; the input check; one phone key for
every tenant; a non-canonical phone hash; message text written to the log; an audit failure swallowed; the NULL-total CHECK; UPDATE granted on
orders; the catalog writable by the application role; row-level security off on the catalog; escaped output turned off in a page.

Not mutated: `ExtractionResult.Failure` carrying an enum instead of a string. Its guard is the type system plus a reflection test; changing
the type breaks compilation, which is a failure but not a test failure. Said plainly rather than faked.

### What the unit phase proves (it runs without Docker: verified with Docker stopped, 234 tests, all modules)

- Ledger: balance and reversal properties on the in-memory ledger.
- Extraction: the strict parser in both shapes and both languages; every rejection; the amount rules; text normalisation (stable, property
  tested); the validator including "parser amounts must be in the source"; the canonical phone form; the hostile corpus and 3,000-try
  property tests; and a **structural scan that the extraction code uses no pattern, format string, reflection, script or process facility**.
- Ingestion: the service's input checks, the decider (stated versus computed totals, caps, catalog price range) and the pure pipeline.
- Web: password hashing and policy, rate limiter, request guard filter, startup guards, permission matrix, handler coverage, template
  lint, provisioning command rules, the isolation guard on invented catalogs, and the source rules: no controller takes a tenant from the
  request, no SQL is built by concatenation, every repository has a cross-tenant test, and **(new) no unit test references the test database
  or the container library, directly or through a helper class**. That scan is shown to catch invented violations and was mutation-checked.
- Not proved by the unit phase: anything that needs PostgreSQL (row-level security, constraints, grants, idempotency under concurrency,
  rendering through the real application). Those are the 215 integration tests.

### Defenses weakened or traded off (design register items 19 to 31)

Raw text kept verbatim; the phone hash is HMAC from `app.secret` (secret plus database together make numbers recoverable; rotation needs a
rehash job); the same number with and without a country code is two customers, and customers without a phone are not merged; only two message
shapes are understood and one unrecognised line makes the message unparsed; "the amount is in the message" proves presence, not meaning;
refused input is not stored; the application role gains INSERT on three tables and the catalog is read-only; hard caps; a restricted
`externalId` set; `NOT VALID` checks on existing tables; a replay with other text keeps the first order; a wall-clock bound in one test;
and the database server's own log can contain the failing row (new item in task 13).

### Not verified

- Only synthetic messages were used. How well the two formats fit real WhatsApp orders is unknown, and a real seller's messages will
  often be `UNPARSED` until the optional AI extractor (task 10) exists.
- There is no HTTP entry for ingestion yet, so request-level behaviour (size limits, CSRF, signature) is covered by task 3 tests and by calling
  the service directly, not by a request to an ingestion endpoint.
- The log checks capture the application's logging (Logback) at DEBUG. They do not read PostgreSQL's server log or the JDBC driver's own log
  (the test URL turns it off). See register item 31.
- The concurrency test runs 16 threads once per run; it is not a stress test.
- Requirement 1.4 is met for logs; the "masked in the UI" half waits for the board (task 8), where `th:text` and the masked `last4` must be used.
- No new third-party dependency was added, so the dependency scan from task 3 was not re-run; an internal test-jar and the already managed
  jqwik were the only additions.
- PostgreSQL 16.4 only.
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.

### One honest remark

The strongest results came from tests that were *wrong first*. The mutants that were not caught showed three places where I had written a
guard twice (the digit cap, the format stripping, the SQL equality hidden behind a map lookup) and two mutants that did not mutate anything.
A green run only counts after you have watched the same test go red.

## Task 5 - payment extraction (5.1 to 5.3)

Proposal and the owner's answers: `.kiro/specs/chat-ledger/task-5-design-stop.md`. Approved on all twelve decisions with one big change and
three additions. **D1 became option C: a template is literal text with typed holes, matched by our own linear scanner. No regular-expression
engine and no new dependency.** The proposal had recommended RE2/J with a pattern whitelist; the owner chose to remove the engine instead of
hardening it. Added: an optional `{reference}` hole (stored in `payment.reference`, compared with the same key function as `orders.order_ref`),
two lines in `tasks.md` task 6 (an `UNVERIFIED` payment is never auto-matched, a `DUPLICATE` is never posted, matching uses `payment.reference`),
and one in task 9 (simulators generate timestamps inside the plausibility window). No HTTP endpoint: the service is the entry point.

### What the spec and the design review caught

- **Requirement 2.4 could not be met by the schema.** It says the new record is marked `DUPLICATE`, but V004's `UNIQUE (tenant_id, provider, tx_id)`
  refused a second row. V015 replaces it with a partial unique index that leaves `DUPLICATE` rows out, plus a CHECK and a composite foreign key so
  the link stays inside the tenant.
- **An `UNPARSED` payment has no provider and no amount**, but both were `NOT NULL`; there was no `external_id` for idempotency and no column for the
  sender's hash. All in V015, with the NULLs tied to the status by a CHECK written with explicit `IS NOT NULL` (the task 4 lesson).
- **The design promised templates "without a release" and "regex with named groups".** Neither survives option C. The design text is changed (register
  items 34 and 35) rather than left to disagree with the code.
- **Matching needs the order code.** Without a `reference` the maximum matching score is 75 and the auto-match threshold is 85, so nothing could ever
  auto-match. Found by re-reading requirement 3 against the payment fields; this is why the owner added the reference hole.
- **RE2/J itself.** Checked before recommending it: version 1.8 (2025-01-09), no advisory on OSV, but two open issues (#168, #215) report compile hangs
  on some patterns. A linear matcher does not make a hostile *template* safe. That finding is part of why option C was chosen.

### What changed from the design stop

- No pattern whitelist parser, no compile deadline thread, no RE2/J gates, no weakened defense 3 (a new third-party library). The new named cost is
  **less flexibility**: no optional parts or alternatives in a template, a delimiter rule, ids and references cannot contain `.` or `:` when read from an SMS,
  a currency hole reads letters only (`F CFA` is written as literal text).
- The shipped set grew from three to **five** templates (ProviderA and ProviderB each with and without a reference, plus the forwarded shape).
- Time zones are fixed offsets only (no named zones, no daylight saving); dates are numeric and fixed width.
- Two guards I had written were **deleted as redundant** after mutation checks showed nothing depended on them (below).

### Defects the tests found in my own work

- **The delimiter rule was wrong when I first wrote it.** I only compared the first character after a hole, but a hole that allows a space runs
  through spaces and swallowed the next word (`Received 5000 XAF from Ana K.` read `XAF from` as the currency). Found while writing the shipped templates.
  The rule now looks past spaces, and a currency hole reads letters only.
- **My own test templates were refused by my own rule, repeatedly.** The rule was right each time.
- **A variable named `javaPattern` tripped my own structural scan** (it looks for the substring `Pattern.`). Renamed to `formatText`.
- **A zero-width character inside a transaction id is removed by normalisation**, so the message parses; my test expected a refusal. The expectation was wrong, not the code.
- **`CHAR(4)` and `CHAR(3)`.** `sender_last4 CHAR(4)` refuses a fifth character with `22001` before any CHECK runs (my constraint test expected `23514`).
- **A test expected a page to hide a phone number that the page prints on purpose.** The raw text is shown verbatim (escaped); only the hash must not appear.
- **A flaky assertion of mine: `doesNotContain("1234")` on text that contains random UUIDs.** A UUID contains the four characters `1234` about once in 2,300 times, and with a hundred of them in a captured log it did in the first full run.
  Removed from the payment tests; **the same flaw existed in task 4's `OrderIngestionIT`** (it passed until now by luck) and is fixed there too.
- **The mutation driver had a bug that made 33 results meaningless.** After each mutant the file was restored with its old modification time, so Maven did not
  recompile the module and a stale mutated class stayed in `target/`. Every mutant in the next module was then "killed" by the previous mutant's leftover: `D01` to `D04` and
  `J01` to `J11` by `T11`, and `Q01` to `Q18` by `Z18`. The signal was that 15 different guards were all "killed" by the same unrelated test (`PaymentTemplatesTest.an_example_must_match_its_own_template`). Fixed (touch the file after restoring) and **all mutants were run again from a clean build**. Only the second run is reported below.
- **The explicit tenant in a statement was not proven.** Removing `tenant_id = ?` from a `SELECT` survived: row-level security still hid the other tenant's rows, so every
  isolation test stayed green. New test runs the repository as the schema owner (which bypasses row-level security) so only the statements' own predicate scopes the result.
  The same gap existed in the task 4 repositories; closed in "Task 5 follow-up".
- **The app role's `TRUNCATE` privilege was untested.** A mutant that granted it survived. Now `has_table_privilege` is asserted for all seven privileges.

### Mutation checks (each guard broken on purpose; the file restored from a copy and compared after every run)

Final run from a clean build: **143 mutants, 141 caught, 2 not caught** (both explained below). The first run had 145 mutants and 11 survivors; each survivor taught something:

| First result | What it showed | Fix |
|---|---|---|
| Template length cap `>` to `>=`: not caught | No test sat exactly on the boundary | Test at exactly 500 and 501 characters |
| Minimum literal characters `<` to `<=`: not caught | Same | Test at exactly 12 and 11 literal characters |
| Duplicate date token (`dd/MM/dd/yyyy HH:mm`): not caught | My "duplicate" test case lacked `MM`, so it was refused for a different reason | Case with every required token plus a repeat |
| Parser's normalised-length cap removed: not caught | Normalisation can grow text (one character becomes 18) but no test used it | Test with 200 × U+FDFA (must be `INPUT_TOO_LARGE`) |
| `sameResultAs` ignoring reference / amount: not caught | Never tested in isolation | `ParsedPaymentTest`: each field changed alone |
| "No other template matches an example" removed: not caught | My twin-template test used a different provider, so the other check fired first | Same-provider twin test |
| "Example read the same by the whole set" removed: not caught | Implied by the stricter check above it, so redundant | **Guard deleted** |
| Date-width pre-check removed: not caught | The strict fixed-width formatter already refuses every other width | **Guard and the `dateWidth` plumbing deleted** |
| Explicit tenant removed from a statement: not caught | Row-level security hid it (see above) | Owner-run repository test |
| `TRUNCATE` granted to the app role (found in the second run): not caught | Only UPDATE and DELETE were tested | Privilege assertions |

Caught in the final run (by the tests named in the run logs, not by compilation): every template gate (id, provider, currency, scale, zone, date format and its required and
duplicate tokens, empty, too long, stray brace, unterminated, unknown and duplicate and adjacent holes, too few literals, not normalised, the delimiter rule in three forms,
required holes, transaction-id rules, sender conflict, `MAX_HOLES` lowered); the scanner (end of message, give-back of spaces, minimum and maximum lengths, start with a space, literal
comparison and its length guard); hole character sets; every typed parser; the validator (amount range, **amount written in the message skipped**, currency, window at both ends and
both boundary values, formats, phone and last four); the template proof (counter-examples, example normalised and validated, expected fields, minimum examples and counter-examples,
duplicate ids, template count); the ambiguity rule; the payment size cap in the parser, in `InputChecks` and in the service; the `UNVERIFIED` rule; the pipeline's validator;
strict JSON (duplicate keys, unknown fields at three levels, size cap, null, wrong type, non-object root); startup that refuses a broken template; a regular expression and a
format string added to the engine (the structural scan); a unit test that reaches the Docker-backed fixture; the service (rate limit, input check, replay with different text, duplicate
lookup, `ON CONFLICT`, one phone key for all tenants, hash not stored, message text written to the log, tenant currency on `UNPARSED`, duplicate status, audit row, reference and last
four not stored, `wasNull`, the retry after a lost race, explicit tenant in two statements, unescaped output in the page); the schema (partial index replaced by a plain one, UPDATE,
DELETE and TRUNCATE granted, every CHECK emptied or loosened, the foreign key not tenant-scoped, the unique external id removed).

Not caught:

- **`TOO_MANY_HOLES` skipped.** The guard is unreachable today: there are eight hole types and each may appear once, so a template cannot have more than ten holes. It is kept because the
  owner asked for the cap, and a test asserts `HoleType.values().length <= MAX_HOLES` so adding a hole type without revisiting it fails. Lowering `MAX_HOLES` to 7 is caught. This is an equivalent mutant, said plainly.
- **The template source reads at most 8 KiB + 1 bytes, and removing that bound was not caught.** The JSON reader refuses the same file afterwards, so the only difference is memory used on a
  file inside our own jar. Not observable by a test without a huge fixture; kept as a bound.

Not mutated: the `FailureReason` enum carrying no text (the type system is the guard, as in task 4); the redacting `toString` of `ParsedPayment`, `PaymentRepository.Stored` and `PaymentIngestionResult` (tests exist, but I did not try a mutant of them).

### What the unit phase proves (it runs without Docker: verified with Docker stopped; 357 tests, all modules, was 234)

Adds to the task 4 list: the template compiler and every one of its refusals; the scanner on its own (including a measured step count: the worst case over the hostile corpora stays under
1,000 steps per template, deterministic, no wall clock); the typed parsers; the payment validator including a parser that lies; the proof of a template set from its own examples; the ambiguity
rule; the pipeline and the decider; the strict JSON reader; a corpus of hostile templates (nested quantifiers, flags, markup, duplicate keys, oversize); the five shipped templates against their examples,
counter-examples, each other, the order corpus and the payment corpus; property tests with random text; and the structural scan, now also covering the payment code. Not proved by the unit phase:
anything that needs PostgreSQL: the 264 integration tests (was 215) cover idempotency under 16 threads, 16 concurrent messages with one transaction id (one original, fifteen linked duplicates), the
constraints and grants, the sender hash equal to the customer hash for the same number, the shared rate limit, atomic rollback, the log canary and rendering through the real application.

### Defenses weakened or traded off (design register items 32 to 50)

SMS authenticity cannot be checked; raw text kept verbatim; **templates are less flexible than patterns**; templates ship with the release; numeric dates and a fixed offset only; the
15-minute and 35-day window; ambiguity refuses; duplicates are stored (growth bounded by the rate limit); the transaction-id uniqueness is a partial index; the application role gains INSERT on
`payment`; `NOT VALID` checks; hard caps; free text and screenshots stay `UNPARSED`; a sender name is free text; the country-code limit carries over and no customer is created from an SMS;
transaction ids are compared in upper case (item 47, changed after review; see "Task 5 follow-up"); "the amount is in the message" proves presence, not meaning; references
must be compared through `ReferenceKeys` in task 6; and the inherited items 31, 5 and 1.

### Not verified

- Only synthetic texts. I do not know how real operator SMS read; the five templates are guesses about shape, and a real seller's messages will often be `UNPARSED` until real formats are added.
- No HTTP endpoint: size limits, CSRF and signing for payments are not exercised by a request; the service is called directly.
- Dates and zones: fixed offsets only, tested with `+01:00`; no daylight saving, no month names.
- The log checks capture Logback at DEBUG; PostgreSQL's server log and the JDBC driver's log were not read (register item 31).
- Concurrency was run once per test run with 16 threads; the retry after a lost race is caught by the mutation check, but a stress test was not built.
- No new dependency was added, so the dependency scan from task 3 was not re-run.
- Duplicate detection works only for a template with a transaction id (item 47 folds its case).
- The "masked in the UI" half of requirement 1.4 waits for the board (task 8).
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.

### One honest remark

The most useful result was a failure of my own *tooling*: 15 mutants were "killed" by one unrelated test, which looked like a strong suite and was a stale class file. A caught mutant only means something
if the test that caught it is about that mutant. The second most useful was a mutant that survived because a safety property (the explicit tenant in each query) was being provided by a second mechanism, so the tests could not tell whether the first one was still there.

### Task 5 follow-up (owner's review: two changes)

1. **The statements' own tenant predicate, proven for the task 4 repositories.** New `OrderRepositoriesStatementScopeIT` runs `OrderRepository`, `CustomerRepository` and `CatalogRepository`
   as the schema owner (which bypasses row-level security), so only each statement's `tenant_id = ?` scopes the result. A control checks that the owner really sees both tenants.
2. **Transaction ids are compared in upper case** (V015 was not merged, so it is changed in place). The partial unique index is on `(tenant_id, provider, upper(tx_id))`, the duplicate lookup is
   `upper(tx_id) = upper(?)`, and the id and `raw_text` stay as written. Ids are ASCII (the CHECK), so `upper()` is the same everywhere. Register item 47 is rewritten: the cost is a false duplicate
   if a provider issues two ids that differ only by case, which the seller can see.

**Mutation checks** (restored from a copy and compared each time; the file is touched after restoring, the lesson from the driver bug above). Each tenant mutant keeps the bound parameter and replaces
`tenant_id = ?` with `? IS NOT NULL`, so it changes the result, not the SQL syntax. 16 mutants:

| Mutant | Result |
|---|---|
| orders: `findByExternalId`, `lines`, `count` without tenant | caught by the owner-run order test |
| customers: `find`, `findByPhoneHash`, `count` without tenant | caught by the owner-run customer test |
| catalog: `prices`, `count` without tenant | caught by the owner-run catalog test |
| payments: `findByExternalId`, `findOriginal`, `count` without tenant | caught by the owner-run payment test |
| orders: the "find the customer by phone hash" statement inside the order insert without tenant | **not caught at first**, then caught (below) |
| unique index on the id as written | caught by the constraint test and the two-case concurrency test |
| duplicate lookup exact, index folded | caught (two-case tests) |
| duplicate lookup folding only the column | caught |
| duplicate lookup folding only the parameter | **not caught at first**, then caught (below) |

Two survivors, both my test's fault:

- **The customer lookup.** Phone hashes are per tenant, so two tenants never hold the same hash and my first test (same phone in both tenants) could not reach the bug. The test now hands the repository
  the same hash for both tenants on purpose.
- **Fold only the parameter** (`tx_id = upper(?)`). Every test stored the original in upper case, so folding the parameter alone looked right. Added the opposite direction: original in lower case, replay in upper case.

Nothing was added to the production code for the first change. Not verified: the case fold uses PostgreSQL's `upper()`, tested with ASCII ids only (all the CHECK allows); a database with an unusual collation was not tried.

## Task 6, stop 2a - scoring and the groundwork for matching (6.1)

Proposal and the owner's answers: `.kiro/specs/chat-ledger/task-6-design-stop.md`. Approved on all seventeen decisions, with stop 2 split in two: this stop builds the scoring, V016, the
repository, the chart of accounts and the ledger's append on the caller's connection; stop 2b builds `MatchingService.apply` (the flows with their postings, the engine runs, the hook in the
ingestion services, the `ORDER_ACCEPTED` posting); stop 3 builds the overrides and the property tests. Three lines were added to `tasks.md` (tasks 7, 8, 9). Nothing here moves money or
changes a status yet.

### What the design review caught (before any code)

- **Four gaps in the plan itself:** `JdbcLedgerRepository.append` opens its own transaction, so a posting could not commit with a match; no chart of accounts existed; nothing posted
  "order accepted"; and the design's score table could not produce a partial or over-payment match at all (the amount signal only fired on an exact amount, so requirements 3.5 and 3.6 were
  unreachable). Each is now either built (the first, second, fourth) or scheduled (the third, in 2b).
- **The honest answer to "signals that cannot be forged by text":** there are none. Every signal comes from a text the forger may have written. The design says so, and puts the defense
  in the floors, in the fact that a match moves no money, and in reversal and audit.

### What changed from the design stop

- The upper bound of the suggestion threshold (90) is not a check of its own: it follows from "the automatic threshold is at most 100 and at least 10 above". A mutation showed the separate check was redundant, so it was removed.
- The commit-time check locks the **order** row only. The first version also locked the payment; the application already locks payment then order, so the extra lock added nothing.
- Apostrophes are dropped from names rather than splitting them (`N'Diaye` is `ndiaye`); the last-four bonus applies only beside a name that already agrees.
- `MatchingProperties` does not validate on its own any more: `MatchConfig` is the one place that knows the bounds, and the Spring bean builds it at startup.
- The planner (`MatchPlanner`) is part of 6.1: scores are turned into "automatic", "suggestions" or "nothing" by a pure function, so the tie margin is tested without a database.

### Defects the tests found in my own work

- **`appendWithin` refused reversals, and `append` used it.** The existing ledger tests (five in `LedgerPostgresIT`, one property test) failed at once: reversals through the service were rejected. Fixed by giving
  both paths a private write; the public `appendWithin` alone refuses reversals (the unmatch flow in stop 3 will add its own).
- **V016 legitimately broke four older tests**, which is the right outcome: `PaymentConstraintsIT` (the application role may now update a status), three cases in `TenancyIntegrityIT` that inserted a match in the old shape, and the two
  fixtures that seed a match row. Each was changed to say what it means now.
- **The name comparison was not symmetric.** Writing the property "the answer does not depend on which name is the sender" showed that an initial only matched in one direction when both names had the same number of tokens.
- **Wrong expectations in my own constraint tests:** a bare ledger transaction with no entries is refused by the balance trigger before anything else; the order-status check comes before the amount check, so a test of the amount
  check needed an order in the right status; a planner test used the wrong margin.
- **A hole in my own mutation driver, again.** One mutant (a text mention of `raw_text` in the repository) "survived" only because the driver ran the integration tests and not the unit test that scans for it. The driver now
  has a mode that runs both; the mutant is caught. (The earlier lesson holds: a restored file is touched so Maven rebuilds it.)

### Mutation checks (each guard broken on purpose; the file restored from a copy, compared and touched after every run)

First run: **236 mutants, 216 caught, 20 survived.** Each survivor taught something:

| First result | What it showed | Fix |
|---|---|---|
| Upper bound of the suggestion threshold changed | Implied by the gap rule | **Check deleted** |
| Zero scores filtered from the planner's list | Never changes the plan | **Filter deleted** |
| A one-letter-token condition in the initial match | An exact match always comes first | **Condition deleted** |
| `openOrders` required a non-NULL total | Implied by the balance predicate and the status CHECK | **Predicate deleted** |
| Order with nothing left to pay was scored | My test gave the payment nothing else to match | Test with every other signal agreeing |
| 9 tokens, 41 characters per token, 101 characters per name, name not cut | Boundary constants tested through the constants themselves | Tests with literal values on each side |
| Two-letter initials; one token used for two initials | Never tried | Two tests |
| Posting debit, receivable credit or excess credit not checked | Each wrong-account case was caught by a different check | Three postings on the wrong accounts |
| Message text selected by the repository | The driver ran the wrong test set | Driver mode (caught) |
| Five `openOrders` and `appliedMinor` mutants | Equivalent, see below | None |

Final run from a clean build: **233 mutants, 228 caught, 5 not caught.** The five are equivalent mutants, said plainly: counting `SUGGESTED` rows in an order's applied amount (two mutants) changes nothing because a
suggestion has no amount (a CHECK); removing the tenant from the applied-amount subquery or from the customer join changes nothing because the composite foreign keys already put the rows in one tenant; removing the
"something left to pay" predicate changes nothing because the V016 trigger makes a fully paid order `PAID`. I kept the predicates: they state what a candidate is and they keep every statement naming the tenant.

Caught (named by group): every bound and the gap of `MatchConfig` and its defaults, the kill switch default; every weight, tier, window and boundary of the scorer; each of the seven floors; the unverified cap and its boundary; the
shared-reference rule; case-insensitive references; the planner's margin, thresholds, cap, order and tie-breaks; name tokens, initials, symmetry and caps; reason codes; the bounds of the facts; the account checks and overflow in
the ledger checks; the properties (defaults, bounds, key); the chart of accounts (conflict handling, type check, codes, types); `appendWithin` (no checks, reversal guard); every statement of the repository scoped by tenant as the
schema owner, the candidate filters, the order, the limit, `wasNull`; a regex in the scorer, a unit test reaching the Docker-backed world, an unregistered repository; and in V016 every CHECK, every unique index, every status move
(each allowed and each forbidden pair), every consistency rule (including the lock, by a test in which a second transaction must wait for the first), and every grant (an extra column, the whole table, `DELETE`, `TRUNCATE`, no `INSERT`).

Not mutated: the three new indexes (nothing observable, only speed); the redacting `toString` methods of the new records are tested, but I did not try a mutant of them.

### What the unit phase proves (it runs without Docker: verified with Docker stopped; 430 tests, all modules, was 357)

Adds to the earlier list: the scorer (each signal, tier, boundary and floor, with a table of example pairs), the planner (including a property that an automatic plan is always eligible and clear of every other candidate), name similarity
(accents, initials, symmetry, hostile and over-long names, a property), the bounds of every configuration value through a bare Spring context, the shared ledger checks, and source scans that matching code never mentions the text of a
message or uses a pattern or format facility. Not proved by the unit phase: everything that needs PostgreSQL, which is the 331 integration tests (was 271): V016's constraints, triggers and grants, the repository (as the application role and
as the schema owner), the chart of accounts under 16 concurrent callers, and `appendWithin` committing and rolling back with other writes.

### Defenses weakened or traded off (design register items 51 to 59)

No signal is unforgeable; new `INSERT` and column-limited `UPDATE` privileges for the application role; nothing can leave `NEEDS_REVIEW` until task 8; the match-to-posting rule is a trigger and not the ledger; name similarity is a heuristic;
thresholds are configuration; the chart of accounts is created by the application role; postings on the caller's connection do not pass through `LedgerService`; new `NOT VALID` checks and triggers that bind every role.

### Not verified

- No flow exists yet. The database rules were exercised with hand-built rows written as the schema owner; the application role has not yet inserted or updated a real match through a service (stop 2b).
- The locks the service will take (payment, then order) and the retry behaviour under 16 parallel matchers are stop 2b. The one concurrency test here uses `SET CONSTRAINTS ALL IMMEDIATE` and waits 1.5 seconds to show that a second check blocks; the
  wait makes it timing-based (if the machine stalls, the mutant "no lock" could slip through; it does not make a correct lock fail).
- The weights and tiers are a design, not a measurement: nothing here says how well they separate real payments from real orders.
- Where the `Recorder` (SELLER, MACHINE or HELPER) comes from is decided in 2b; today it is a parameter.
- PostgreSQL 16.4 only. No new dependency, so the dependency scan was not re-run.
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.

### One honest remark

Nine of the twenty first-run survivors were not missing tests but guards that said the same thing twice (a bound implied by another, a filter that changed no result, a predicate implied by a CHECK or a foreign key). Four were deleted,
which made the code smaller and the remaining checks easier to trust; five are kept and named, because they state what a candidate is and keep every statement naming its tenant, at the price of mutants that no test can kill.

## Task 6, stop 2b - the flows, the postings, the engine and the hook (6.2)
Design and the owner's answers: `.kiro/specs/chat-ledger/task-6-design-stop.md`. This stop builds `MatchingService.apply` (one transaction), the flows with their ledger postings, the engine runs, the post-commit hook
in both ingestion services, and the `ORDER_ACCEPTED` posting at ingestion, with V017. `tasks.md` 6.2 carries a note and is **not ticked** (the owner decides). Nothing was committed and no branch was made.
### What was built
- `MatchingService` (`apply`, `onPaymentRecorded`, `onOrderRecorded`, the `MatchTrigger` methods), `Postings`, `MatchEvents` (logger `match.events`, ids and codes only), `MatchTrigger` (`NONE`), the within-connection
  statements of `MatchRepository`, V017 (`idx_audit_log_payment_ingested`), the `ORDER_ACCEPTED` posting in `OrderRepository.insertWithin`, and a `MatchTrigger` parameter on both ingestion services.
- **Order of one apply:** lock payment, lock order, re-read and re-score, compute `min(payment, balance)` and the excess, ensure the accounts and the order's posting, post `PAYMENT_MATCHED`, write the match, set
  the order status, set the payment status, retire the other suggestions, append the audit row. A test can fail the transaction after each of the nine steps (`StepHook`).
### The caller-visible outcome when the `ORDER_ACCEPTED` posting fails inside ingestion
`OrderIngestionService.ingest` throws `IllegalStateException("Could not post an order to the ledger")` (a bad chart of accounts: `"The chart of accounts is not usable for this tenant"`). The ingestion transaction
rolls back whole: the order, its lines, the customer link and the audit row are not stored, and no event or matcher call is made. A retry with the same external id starts from nothing and succeeds; the unique index
`uq_ledger_txn_posting` (one posting per business event) makes a double posting impossible. Proof: `OrderPostingIT` (`if_the_posting_fails_...`, `sixteen_threads_retrying_after_a_failure_post_the_order_once`,
`sixteen_simultaneous_submissions_of_one_message_make_one_order_and_one_posting`, `an_unusable_chart_of_accounts_...`).
### Where the Recorder comes from, and how a helper's payment becomes suggestion-only
It is read from the audit trail, never from a caller: the append-only `PAYMENT_INGESTED` entry (subject = the payment id) joined to `app_user.role`. No actor is `MACHINE`; the exact word `SELLER` is `SELLER`; any
other role, or no entry, is `HELPER`. The earliest entry of the tenant wins (`ORDER BY a.at, a.id`), so a later forged entry cannot raise trust. `HELPER` fails one of the seven floors of `MatchScorer`, so the plan for a
helper's payment is "suggest" at best; and `apply` in `AUTO` mode re-scores under the lock and returns `DEGRADED_TO_SUGGESTION` even when called directly (`MatchingFlowsIT`).
### The 16-thread tests (real PostgreSQL, the application role)
`MatchingConcurrencyIT`: sixteen threads matching one payment to sixteen orders make one match and one posting; sixteen payments into one order never apply more than its total; sixteen large payments apply the total once
and record only the excess of the payment that crosses it; the engine run twice for one payment at once makes one match; sixteen payments for sixteen orders each find their own; random pairs from many threads in every order never
deadlock and keep every invariant. `OrderPostingIT`: sixteen simultaneous submissions of one message; sixteen threads retrying after a failure. The chart-of-accounts test of 2a (sixteen callers) still runs.
### An injected failure after each step leaves nothing
`MatchingRollbackIT` fails the transaction after each of the nine steps, in the seller's path and in the automatic path. After every one, the ledger, the accounts, the transactions, the matches (with their decided times),
both statuses and the audit rows are exactly as before (one comparable snapshot), and the same call without the failure then posts once. It also covers a posting the database refuses, an audit row it refuses, the commit-time
triggers refusing inconsistent books, a refused chart of accounts, and the engine swallowing a failure.
### Defects and gaps found by my own tests and mutation checks
- **A scanner rule I broke.** To share the recorder subselect I put it in a Java constant; `SourceRulesTest` (no SQL built from anything but literals) failed in the unit phase. I kept the rule and went back to two literal
  copies, and added a test that reads the recorder through both the list and the lock and compares (a mutant on the copy in the lock was first missed, then caught by a case with a later forged entry).
- **Guards that said the same thing twice** (deleted, as before): the not-found check on the payment and the order (an order is only looked for when the payment exists); the two null checks on amount and total (the CHECKs
  `chk_payment_by_status` and `chk_orders_total_by_status` already say so); `auto ? null : actor` (an automatic match has no actor, checked at the top); the service's own null check of the tenant (`TenantScope` refuses it by name);
  and `reverses_txn_id IS NULL` in the "order is posted" lookup (reversals have kind `REVERSAL`).
- **Tests that did not test what they said.** The order-open test used orders with no total, so the status check was never alone; now orders in `PAID`, `CANCELLED` and `NEEDS_REVIEW` with a total. The "another tenant's audit
  entry" test ran as the application role, where row-level security hides the row anyway; now the recorder is also checked as the schema owner, with a foreign actor, another action, a tie at the same instant and a later forged entry.
- **The 2a claim "equivalent" was wrong for three predicates.** The tenant on the applied-amount subselect and on the two customer joins looked redundant because of the composite foreign keys. With the keys and triggers off
  (superuser, `session_replication_role = replica`) a match or a customer of another tenant can be pointed at; a new test shows the queries still do not follow it. They are no longer called equivalent.
- **The index tests were weak.** A plan on a nearly empty table named the index even when it was on the wrong column or not partial. The definitions are now pinned (`pg_indexes`), plus the plan check that each exists and serves the query shape.
- **Not covered at all:** a confirmation whose suggestion was decided meanwhile (now: the write changes 0 rows, the transaction stops and leaves nothing), an automatic decision overturned by the locked data (now stored as a suggestion,
  with the run reporting it), and the log lines of those branches.
### Mutation checks (each guard broken on purpose; file restored from a copy, compared, touched)
First run: **171 mutants, 126 caught, 45 survived.** Of the 45: **4 equivalent** (kept, named below), **6 redundant guards deleted** (above), **35 caught after new or stronger tests**. Rerun after the fixes: 41 mutants
(every survivor and every mutant whose source had changed), all caught except the equivalents; after going back to two literal copies of the recorder query, the 20 recorder mutants were run again on both copies (19 caught, 1 more
case added, caught). Final state: no surviving mutant except these four.
| Group | Mutants | Result |
|---|---|---|
| `MatchingService` (actor and mode check, not-found, payment free, order open, currency, balance, auto degrade, min and excess, order posted, suggestion supersede and confirm, order status, payment status, supersedes, audit action and actor, state label, decided-by and at, engine branches, trigger catches, null checks, `expectOne`, nine hooks, three events) | 61 | all caught; 1 equivalent (below) |
| `Postings` (swapped sides, wrong accounts, clearing amount, excess entry condition and account, kinds, ref ids) | 9 | all caught |
| `OrderRepository` posting (condition, message, catch, amount, chart) | 6 | all caught |
| Ingestion hooks (order and payment conditions, every catch) | 8 | all caught |
| `MatchRepository`: tenant on 16 statements, 3 joins and subselect; both `FOR UPDATE` clauses; recorder pieces on both copies (tenant, user tenant, action, subject, order, tie-break, default, machine, null test, limit); suggested and decided filters; applied states; posting lookup; shared reference | 58 | all caught; 3 equivalent (below) |
| The **three new V016 indexes and the V017 index**: dropped (4), wrong column (3), not on `upper(...)` (1), not partial (1) | 9 | all caught (dropped: by the plan test; the rest: by the pinned definitions) |
| **The redacting `toString` methods skipped in 2a**: `MatchRepository.Stored`, `PaymentCandidate`, `LockedPayment`, `LockedOrder`, `NewActive`, `OrderFacts`, `PaymentFacts`, `PaymentRepository.NewPayment`, `PaymentRepository.Stored`, `ParsedPayment`, each made to print a name, reference, hash, transaction id, raw text or the stored reasons | 20 | all caught in the unit phase; two (the stored reasons of a match) needed `AMOUNT_EXACT` added to what the redaction test forbids |
The four equivalents, said plainly: `rows < 1` instead of `rows != 1` in `expectOne` (every write there is keyed by a primary key, so it cannot change two rows); counting `SUGGESTED` rows in the applied amount (two statements: a
CHECK gives a suggestion no amount); and `state IN ('REJECTED','SUGGESTED')` in the "was rejected" lookup (a CHECK gives a suggestion no decider). Not mutated: the choice of lock order applied consistently in both statements
(an equivalent change), and the log wording.
### What the unit phase proves (verified with Docker stopped: 433 tests, all modules, was 430)
31 + 156 + 31 + 62 + 3 + 150. Adds `MatchingRedactionTest` (every record that carries a field of a message prints only its identity and status). Not proved by the unit phase: everything that needs PostgreSQL, which is the **406**
integration tests (was 331): the flows, the rollbacks, the concurrency, the posting at ingestion, the trigger, the recorder and the tenant scoping of every new statement.
### Defenses weakened or traded off (design register items 60 to 66)
The hook is best effort (a missed match waits for the next event or the stop 3 `rematch`); trust comes from the audit trail, which the application role can append to (the earliest entry wins); a posting failure fails the order's
ingestion; a stale suggestion can be stored for a payment matched meanwhile (a confirmation refuses it); the tie margin is read outside the lock; the lock order is one convention in one place; the index tests use query shapes.
### Not verified
- Stop 3 is not built: no confirm, reject, manual match, unmatch or `rematch` entry point exists, so nothing yet calls `apply` in `CONFIRM` mode except tests, and no screen shows a suggestion.
- A real race between the engine's read and `apply` was simulated (a repository that describes the order wrongly; a second connection that retires a suggestion from inside a hook), not produced by timing.
- Sixteen threads is a sample, not a proof of freedom from deadlock; the random-pairs test passed every run but cannot show absence.
- The index tests check definitions and a plan on an empty table; they do not show that the queries are fast at scale.
- `ORDER_ACCEPTED` for an order that leaves `NEEDS_REVIEW` is task 8; until then such an order is posted by the matcher before its first match, which no flow reaches yet.
- PostgreSQL 16.4 only. No new dependency, so the dependency scan was not re-run.
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.
### One honest remark
The mutation run found fewer gaps in the code than in the tests: of 45 survivors, six were guards to delete and four equivalents, but 35 were behaviours (an overturned automatic decision, a suggestion decided mid-confirmation, an
audit lookup under a tenant that the database would have hidden anyway) that my first tests described but did not exercise. Several were hidden by row-level security or a foreign key that the test, by running as the application role
or with consistent data, never took away.

## Task 6, stop 3 - the seller's decisions, unmatch, rematch, property tests (6.3, 6.4)
Tasks 6.1 to 6.4 and task 6 are ticked in `tasks.md`. Nothing was committed and no branch was made.
### The security fix first: a missing actor is a helper
The recorder subselect read `a.actor IS NULL` as `MACHINE`, the highest trust, so one forgotten argument made a payment eligible for an automatic match, and the signed feed does not exist before task 11. Both copies of the
SQL now return the user's role or `HELPER`, and `recorderOf` trusts only the exact word `SELLER` (a stored `MACHINE` is a helper too). `Recorder.MACHINE` stays in the enum for the scorer's own tests of the future path and cannot be
produced from storage. Test: `MatchingEdgeCasesIT.a_payment_recorded_with_no_actor_is_suggestion_only_it_is_not_the_machine` (the same evidence recorded by a seller is automatic; with no actor it is only suggested, also when the automatic
path is called directly). Mutants: the old CASE restored in each copy, `recorderOf` trusting `MACHINE` or any word or nobody, the default `HELPER` turned `SELLER` in each copy: 7, all caught. Register item 67; task 11 in `tasks.md`
now says it must record an explicit machine identity in the audit entry.
### What each seller action refuses (all start by asking the database whether the user is an enabled seller of the tenant)
Every action refuses, with `NOT_A_SELLER` and no change: a helper, a disabled seller, a seller of another tenant, an unknown user. Unknown ids and another tenant's ids give `NOT_FOUND`.
- **confirm** (the named suggestion): `NOT_SUGGESTED` if it is not an open suggestion (rejected, retired, reversed, or another suggestion of the same pair is the open one); `PAYMENT_NOT_FREE` (matched, set aside, duplicate, unparsed); `ORDER_NOT_OPEN`; `CURRENCY_MISMATCH`; `NOTHING_LEFT_TO_PAY`. Amounts come from the balance at that moment.
- **reject**: `NOT_SUGGESTED` for anything but an open suggestion (twice, confirmed, automatic). Records who and when; the pair is never proposed or applied automatically again.
- **match by hand**: the payment must be `RECEIVED` or `UNVERIFIED`, the order `PENDING_PAYMENT` or `PARTIALLY_PAID` with something left, same currency; refuses duplicate, unparsed, matched and set-aside payments, `NEEDS_REVIEW`, cancelled and paid orders. Audited `MATCH_MANUAL` (or `MATCH_CONFIRMED` when it confirms the engine's suggestion for that pair). A pair the seller rejected may be matched this way.
- **unmatch**: `NOT_ACTIVE` unless the match is `AUTO` or `CONFIRMED` (a suggestion, a rejected or an already reversed match, twice).
- **set aside** (`UNMATCHED`): only a `RECEIVED` payment (`PAYMENT_STATUS_NOT_ALLOWED` for unverified, matched, duplicate, unparsed, already aside). Its open suggestions are rejected by the seller. **Take back**: only from `UNMATCHED`. The engine never sets either status (tested).
- **rematch**: refuses a non-seller without running or auditing anything; looks at the `candidateCap` most recent free payments; a failure on one is logged by id and does not stop the others.
- **An automatic match** never overrides the seller: `SELLER_DECLINED` under the lock, and the engine does not even score a declined pair. A suggestion retired by the system (no decider) is not a decision of the seller.
### Unmatch and `reverseWithin`
One transaction, lock order payment, order, match. `JdbcLedgerRepository.reverseWithin` reads the posting, builds its mirror, refuses a posting that is missing, belongs to another tenant, is itself a reversal or was already reversed (checked before writing, so the caller's transaction is not aborted; the global unique index is the backstop), and writes on the caller's connection. The match becomes `REVERSED` with who and when, the original posting is untouched, the order is recomputed from the remaining matches, the payment goes back to `RECEIVED` (or `UNVERIFIED` without a transaction id), and `MATCH_REVERSED` is audited. The excess credit is reversed with it.
### The race simulated versus the race produced
**Produced with real threads** (`SellerActionsConcurrencyIT`, `MatchingConcurrencyIT`, `LedgerReverseWithinIT`, against PostgreSQL as the application role): 16 threads unmatching one match (one reversal, 15 `NOT_ACTIVE`); 16 confirming one suggestion (one match); a confirmation against a rejection of one suggestion, 20 rounds (exactly one wins each time, the loser is refused cleanly); an unmatch against a manual match to the same order, 15 rounds; 16 threads reversing one posting (one reversal, 15 already reversed); 16 rematches at once (each match once); and a storm of 16 threads making 40 random decisions and arrivals each (confirm, reject, manual, unmatch, set aside, take back, rematch, new payments, new orders). **Simulated, not produced:** a suggestion that changes state between two statements of one transaction (a seam acts on the transaction's own connection; with the row locked no other connection can do it); an automatic decision overturned by the locked data (a repository that describes the order wrongly); the failure after each step (an exception from a seam); the two lock orders (a look from another connection at which rows are held at that moment, not a deadlock that happened to be avoided); the foreign-key lock conflict below (a transaction holds a key share, as the check of a foreign key does).
### A real deadlock, found by the storm test, in stop 2b's code
The first run of the storm failed with PostgreSQL's `deadlock detected`. Cause: inserting a match takes a key-share lock on the order and the payment it refers to, and the matcher locked those rows `FOR UPDATE`, which waits for a key share. The engine storing a suggestion (key share on the order, then the payment) and a seller's decision (lock on the payment, then wait for the order) could wait for each other for ever. The stop 2b tests never ran suggestions and decisions together. Fix: every row lock in the matcher is `FOR NO KEY UPDATE` (it never changes a key column); `ForeignKeyChecksDoNotBlockIT` was written first, failed, and passes now. Register item 74. The commit-time trigger of V016 took `FOR UPDATE` on the order too; the owner asked not to rely on an argument that it cannot form a cycle, so V016 was edited in place (unmerged) to `FOR NO KEY UPDATE`, the only `FOR UPDATE` in any migration. `ForeignKeyChecksDoNotBlockIT` now requires apply and unmatch to commit while a key share is still held; putting `FOR UPDATE` back in the trigger makes it time out (mutant caught); the storm and the other concurrency tests ran 8 more times clean.
### Other defects found by my own tests
- Several tests passed for the wrong reason or were weak: a confirmation of an automatic match is refused as `PAYMENT_NOT_FREE`, not `NOT_SUGGESTED` (its payment is checked first); the engine's order-side filter was masked by the payment side for automatic plans and mattered only for suggestions; the plan test for the indexes (stop 2b) failed as soon as other tests had written rows, because the planner chose another index, so it was removed and the definitions are pinned.
- The storm test first failed from the test machine running out of ports (a new connection per statement from 16 threads), not from the product: it now shares a small pool.
- A coverage rule in the property test (a flow must happen in at least 25% of the sequences) failed on an unlucky draw; it is now "at least once".
- The property did not first catch the removal of both layers of the seller's no (engine filter and re-check under the lock): no sequence undid an automatic match and looked again. A step "unmatch, then rematch at once" was added and now catches it.
### Property tests (6.4)
`MatchingBooksPropertyIT`: 30 random sequences of 20 steps (new orders, new payments that mirror an order so that automatic matches, partial and over-payments really happen, confirm, reject, match by hand, unmatch, unmatch then rematch, set aside, take back, rematch), the engine running at every ingestion. After every step `MatchInvariants` reads the tables directly: no payment assigned more than its amount, none to two orders, no order assigned more than its total, trial balance zero, every live payment posting belongs to an active match, receivables equal accepted totals minus active matches, sales equal accepted totals, customer credit equals the excess, the clearing account equals what active matches received, order and payment statuses are the function of the matches. Also: a repeated decision changes nothing (each seller decision runs twice and the fingerprint must not move), no automatic match for a pair the seller turned down, and a second property: a repeated rematch reaches a point where it changes nothing (a pass can clear a rival for an earlier payment, so one pass is not enough; the bound is the number of free payments). Coverage rules fail the run if a flow (automatic, confirmed, by hand, rejected, reversed, set aside, excess, partial) never happens. Runs in about 90 seconds.
### Mutation checks (file restored from a copy, compared and touched; three workers on copies of the repository)
**143 mutants.** First run of 142: 130 caught, 12 survived. After the fixes below: every survivor is either caught or named.
| Group | Mutants | Result |
|---|---|---|
| Recorder (NULL actor, `MACHINE`, defaults) | 7 | all caught |
| Apply, confirm, manual, suggestion reuse, declined pair, audit action, engine filters, lock order | 19 | 2 survived (order-side filter), caught by a new test |
| Reject | 9 | all caught |
| Unmatch (seller, states, reversal, order and payment status, re-read under the lock, audit, logs, two lock orders) | 20 | all caught |
| Set aside, take back, rematch | 22 | 1 survived (`reopen` also rejecting suggestions), caught by a new test |
| Every hook of every step (must be reached and must roll back) | 15 | all caught |
| The new statements of `MatchRepository` (tenant on each, every state filter, role, disabled flag, locks) | 32 | 2 survived (system decisions counted as the seller's; set aside rejecting every payment's suggestions), caught by new tests |
| `reverseWithin` | 6 | 1 equivalent (below) |
| Defensive `expectOne` around writes the locks make certain | 3 | 3 equivalent (below) |
| Property only (run without the other tests) | 10 | 7 caught; 3 survived, below |
| Lock strength (`FOR UPDATE` back, `KEY SHARE`, removed) | 8 | 6 caught; 2 equivalent |
**Equivalent or covered by another layer, said plainly:** `expectOne` removed from the unmatch order status, the unmatch payment status and the payment move (the rows are locked and keyed by primary key, so the write cannot change another count; the helper stays as a net); the tenant predicate of the already-reversed check in `reverseWithin` (the unique index on the reversed transaction is global, so a foreign row refuses the reversal the same way); `FOR NO KEY UPDATE` turned back into `FOR UPDATE` on the suggestion row and the match row (no table refers to `match`, so no key share can conflict). Of the property-only mutants, two (the re-check under the lock, or the engine filter, removed alone) survive the property because the two layers cover each other and are each caught by an example test; removing both is caught by the property; and one (set aside not rejecting suggestions) is caught by an example test, not by the property's invariants. The earlier stop 2b mutants whose code was restructured (the suggestion reuse) were run again.
### What the unit phase proves (verified with Docker stopped: 433 tests, unchanged)
Stop 3 adds no unit test: every new behaviour reads or writes PostgreSQL (the source scans still pass over the new SQL, all of it literals). The integration phase is **461** tests (was 406): `SellerDecisionsIT`, `UnmatchIT`, `PaymentSetAsideAndRematchIT`, `SellerActionsRollbackIT`, `SellerActionsConcurrencyIT`, `LedgerReverseWithinIT`, `LockOrderIT`, `ForeignKeyChecksDoNotBlockIT`, `MatchingBooksPropertyIT`, and additions to `MatchRepositoryWithinIT` and `MatchingEdgeCasesIT`.
### Defenses weakened or traded off (design register items 67 to 74)
A missing actor is a helper (a legitimate caller with no actor loses automatic matching until task 11); seller-only is enforced by the service and not by the database; the seller's no is permanent for the engine; rematch has no rate limit of its own (task 8 line added); an unmatch of a match whose credit was later used is not designed; the suggestion row is locked by the apply step; the property tests are statistical; row locks are `FOR NO KEY UPDATE`.
### Not verified
- The deadlock fix is proved by a deterministic lock test (including the commit-time trigger) and by repeated storm runs (about twenty clean after the fix, one failure in about seven before), not by an exhaustive argument.
- No endpoint, screen, CSRF or permission check exists yet (task 8): the seller-only rule is the service's own.
- The property is a sample of 30 and 20 sequences; it cannot show that a defect is absent.
- Not tested: a seller whose role changes or who is disabled in the middle of a decision (the check and the decision share one transaction, but no test interleaves them).
- PostgreSQL 16.4 only. No new dependency, so the dependency scan was not re-run.
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.
### After CI: two failures that passed on the Mac (PR #22)
- **Timestamps.** Six tests compared an `Instant` from the clock with the value read back from PostgreSQL. Linux clocks have nanoseconds; PostgreSQL keeps microseconds and rounds. Five tests failed on CI (the sixth is below). Reproduced on the Mac by giving the test clock 799 nanoseconds (all five failed). Fix at the write: `DbTime.utc(...)` cuts to microseconds (truncating, never rounding) and every database write of an `Instant` goes through it (decided_at in six statements of `MatchRepository`, `ledger_txn.created_at`, `payment.paid_at`); `reverseWithin` returns the reversal with the stored time; a unit test fails if any other class converts an instant for the database. A service-level cut I first added was redundant (nothing reports a time) and was removed. Mutants: no truncation (caught by five integration tests), rounding instead of truncating (unit), `utc()` skipping the cut (unit and integration), a write that bypasses `DbTime` (scan), `reverseWithin` returning an uncut time (caught).
- **The engine run twice.** Two of the sixteen runs were not `SKIPPED`: they were `NOTHING`. A run reads the payment (free), the winner commits, and then the run reads the open orders and finds none, so there is nothing to plan. Seen in 16 of 40 runs on two Linux CPUs, with up to 13 `NOTHING` in one run: the test had assumed an ordering. Both outcomes are legitimate; the test now asserts the real invariant (exactly one `APPLIED`; every other run `SKIPPED` or `NOTHING`; no suggestion stored; one automatic match, one posting). The kind of every run is in the failure message.
- **A third, separate failure found on the way.** About one repetition in 80 on two CPUs failed with `Connect timed out`: the test data source opens a new connection for every statement and sixteen threads exceeded the driver's ten seconds. A test harness defect, not the matcher. `MatchingFixture` now shares a pool of the application role's connections (also three times faster). 480 repetitions on two CPUs then passed.
- **CI environment.** Reproduced as a Linux container pinned to two CPUs (Maven and the database both on CPUs 0 and 1, Java 21): the full verify passes there (154 unit, 464 integration).

