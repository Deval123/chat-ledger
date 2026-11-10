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


## Task 8 - board and summary UI (stop 2)

The owner approved D1 to D22 of `.kiro/specs/chat-ledger/task-8-design-stop.md` with five conditions. Work stays on `task-8-board`; nothing was committed by me. Task 1 in `tasks.md` is ticked (`- [x] 1. Project skeleton`), checked at the start of this stop and again at the end.

### The five conditions
1. **`PhoneMask.maskText` counts digits across separators.** Digits joined by up to three separator characters in a row (space, dot, dash, parentheses, U+00A0, U+202F) are one number; 7 or more digits keep the last four, the others become `•`. Tested with `6 99 00 12 34`, `699.001.234`, `(+237) 699-001-234` (the leading `(+` stays outside) and `+999 000 000 1234`, plus each separator alone. Heuristic, named in register item 79.
2. **htmx from the official release.** v2.0.11 (the latest 2.x; 4.x was rejected because the configuration keys and the CSP contract are htmx 2). Downloaded from the GitHub release, SHA-256 computed here (`d6fdc75f…f717`, 52,182 bytes, equal to the release asset's digest), licence Zero-Clause BSD read from the source archive's `LICENSE` (archive SHA-256 `665124df…a1b`). File, licence and `web/third-party/htmx/PROVENANCE.md` are in the tree; `HtmxProvenanceTest` fails if the hash, size, version, note or licence changes, or if another script appears among the static files. I reached the release; nothing was guessed.
3. **puppeteer-core only as a dev tool.** `tools/browser-check/` (puppeteer-core 25.16.0, exact, lock file with 25 packages), installed with `npm ci --ignore-scripts`, uses the installed Chrome, outside the Maven build and not run by CI. What I ran is below.
4. **V018 and the approval.** The freezing trigger refuses any change of `total_minor` outside `NEEDS_REVIEW` to `PENDING_PAYMENT`, for every role including the owner; the status trigger allows that one move out of review; a deferred trigger refuses the move at commit without this tenant's `ORDER_ACCEPTED` posting. `OrderApprovalService.approve` is one transaction: enabled seller, lock, status, total 1 to 10^12, total and status, accounts, posting, audit `ORDER_APPROVED`; the matcher is asked afterwards. Every guard was mutation-checked (12 trigger mutants, 13 service and page mutants, all killed or deleted as redundant).
5. **HELPER: no raw text, no export, no books.** A 403 test per route (`BoardRoutesIT`, generated from the request mappings) and a test per template (`BoardRenderIT`: nine pages rendered for a helper, none holds the raw text, the masked marker or a phone beyond the last four; the board holds no link to books, downloads or rematch). The repository is not even asked for raw text for a helper.

### What each route refuses
Every route: anonymous is sent to sign-in (POST: 403 or sign-in); a user without the permission gets 403 with the fixed page; every POST without or with a wrong CSRF token (form field or htmx header) gets 403 and changes nothing; an id of another workspace or an unknown id gets 404 (never 403); a bad UUID, column, cursor, page or date gets 400 without echoing the input; every POST counts against 120 actions a minute per user (429 with `Retry-After`).

| Route | Permission (who) | Also refuses |
|---|---|---|
| `GET /app` | `VIEW_BOARD` (seller, helper) | an unknown notice code is ignored, never echoed |
| `GET /app/columns/{column}` | `VIEW_BOARD` | column not one of six, cursor not `micros_uuid`, page outside 1 to 5 (400) |
| `GET /app/orders/{id}`, `GET /app/payments/{id}` | `VIEW_BOARD` | raw text only for a seller, only for unread, review and flagged items |
| `GET /app/orders/{id}/review` | `APPROVE_ORDER_TOTAL` (seller) | an order not in review: back to the order with a sentence |
| `POST /app/orders/{id}/approve` (`total`, then `confirm=yes`) | `APPROVE_ORDER_TOTAL` | not an amount, 0 or above 10^12, not in review (sentence); without the exact word `yes` only the second look is shown and nothing is written |
| `GET /app/payments/{id}/match`, `POST /app/matches/manual` | `CONFIRM_OR_OVERRIDE_MATCH` (seller) | a payment that is not free; a pair across workspaces (404) |
| `POST /app/matches/{id}/confirm`, `/reject` | `CONFIRM_OR_OVERRIDE_MATCH` | a suggestion no longer waiting, a payment no longer free (sentence) |
| `GET /app/matches/{id}/undo`, `POST /app/matches/{id}/unmatch` | `REVERSE_TRANSACTION` (seller) | a match not active (sentence) |
| `POST /app/payments/{id}/set-aside`, `/take-back` | `CONFIRM_OR_OVERRIDE_MATCH` | a payment in the wrong status (sentence) |
| `POST /app/rematch` | `CONFIRM_OR_OVERRIDE_MATCH` | 3 a minute and 20 an hour per workspace (429) |
| `POST /app/orders`, `POST /app/payments` (`mid`, `text`) | `RECORD_ORDER`, `RECORD_PAYMENT` (seller, helper) | `mid` not a UUID (400), bodies above 64 KB (413), the ingestion limit (429), the services' input rules (sentence) |
| `GET /app/summary?date=` | `VIEW_BOARD`; amounts only with `VIEW_LEDGER` | a date before 2000 or after today, or not ISO (400) |
| `GET /app/books`, `POST /app/books/check` | `VIEW_LEDGER` (seller) | nothing else |
| `GET /app/export`, `GET /app/export/orders.csv`, `payments.csv` | `EXPORT_DATA` (seller) | more than 366 days, reversed or future dates, before 2000 (400); more than 100,000 rows (back with "narrow the dates", nothing sent, no audit row); 6 per 10 minutes per workspace (429) |
| `GET /app/export/ledger.csv` | `EXPORT_DATA` and `VIEW_LEDGER` | the same |
| `GET /assets/**` | public | no tenant data; unknown files 404 |

### Browser check: what I ran and what I did not
Ran, in headless Chrome (the installed Google Chrome, via puppeteer-core) at 390 by 844, against the real application on a throwaway database (`BrowserCheckFixtureIT`, run only with `-Dbrowser.check=true`). 29 checks, all passing on the last run:
- sign-in lands on the board; the vendored htmx loads and runs under the CSP; no console error or CSP violation in the whole walk;
- no sideways scroll at 390 px; every visible button, link and summary at least 44 px tall; text at least 16 px; pinch zoom not disabled; Tab reaches the order box with a visible outline;
- with htmx: paste an order (answer in place, no navigation, chips updated out of band); the matching payment matches by itself (Matched 1, Awaiting 0); an underpayment waits in Review with Confirm and Reject; Confirm from the card replaces the card and fills the notice; the order is back in Awaiting as "Part paid: 3,000 XAF of 5,000 XAF"; a repeated transaction is flagged; free text is kept as unread; the chips count them; the columns scroll and snap; a 404 answer to an htmx request (sent with `htmx.ajax`, as a stale page would, not by a button) lands in the notice area and leaves the page alone; no history snapshot in storage (one session key with the current path only, register item 93); the unread message is under a closed `<details>`;
- a helper sees the paste forms and no seller control;
- with JavaScript disabled: a paste redirects to the board with the notice; the card has plain buttons; Set aside works and shows its notice.
Screenshots: first screen, the Review column, each of the five columns, the unread detail, the helper's board, the board after a paste without JavaScript (in `tools/browser-check/screenshots/`, not committed).

Not run in a browser: a real phone or a touch device (the viewport is emulated); Safari, Firefox or an old Android browser; a slow network; a screen reader; the summary, books, download, review, approve, undo and picker pages (covered by integration tests only); the wide-screen layout; keyboard use beyond the first field. Found by the walk and fixed: every page asked for `/favicon.ico` and logged an access denial (a favicon is now served under `/assets`). Also added while debugging the notice area: `Accept: text/html` in `hx-headers`, so an error answer is the HTML page whatever htmx sends by default. I did not verify that htmx needed it; the notice-area failure itself was my test passing a `swap` option that overrides the response handling.

### Mutation checks
Three to four workers, each on its own copy of the repository; each mutant applied, the named tests run, the file restored and touched. A kill counts only when the build reports a failing test (the first pass's classifier did not check that, so the whole set was run again with logs kept).

| Group | Mutants | Killed | Deleted as redundant |
|---|---|---|---|
| V018 triggers and grant | 12 | 12 | 0 |
| Approval transaction and second look | 13 | 12 | 1 |
| Permission on each route, role table | 21 | 21 | 0 |
| CSRF on the board's POSTs | 1 | 1 | 0 |
| Public assets | 1 | 1 | 0 |
| Rate limits (defaults, wiring, each route's call, rematch and export at the endpoint) | 23 | 23 | 0 |
| 404 for foreign or unknown ids | 2 | 2 | 0 |
| Helper versus seller filtering | 7 | 7 | 0 |
| Raw text (who, which items, masked) | 7 | 7 | 0 |
| Tenant predicate of each new statement (owner-run tests) | 12 | 12 | 0 |
| Column placement precedence | 10 | 10 | 0 |
| Page size, page cap, cursor paging | 7 | 7 | 0 |
| Cursor, day and notice parsers | 8 | 5 | 3 |
| CSV export (bounds, cap, audit, masking, BOM, numbers) | 14 | 13 | 1 |
| CSV neutralisation (each prefix, starts, quoting, cleaning) | 17 | 17 | 0 |
| Display cleaning | 12 | 11 | 1 |
| PhoneMask | 16 | 16 | 0 |
| MoneyFormat | 7 | 6 | 1 |
| Books check (six comparisons, tenant, cache, log) | 12 | 11 | 1 |
| htmx configuration keys and script | 7 | 7 | 0 |
| Templates, error page, Flagged note | 6 | 6 | 0 |
| **Total** | **215** | **207** | **8** |

The first full pass left 20 survivors. Twelve exposed missing tests, now written: a posting of another tenant with the same reference must not satisfy the commit check; the review page's raw text masked; a tab, a CR, an LF start alone; a tab shown as a space; the parenthesis and both no-break spaces as separators; the books mismatch logged at ERROR (the test had read DEBUG too); a non-dash at a dash position of the cursor; a stored name with a bidi override cleaned and cut on the card; CSV totals unquoted. One more after I added the second look: only the exact `confirm=yes` approves. Eight were guards no test could tell from their absence; they are deleted and named in register item 91. Nothing survives.

### Defects found by my own tests in this stop
- The approve page offered no second look at the formatted amount, which the design promised (found while writing the register); added, with its own mutants.
- `/app/columns/{column}` did not convert lower-case names (400 for every column); a converter was added.
- The export page's list literal did not parse in Thymeleaf (500).
- The page-wide hostile check matched an `on…=` word inside message text that was correctly escaped; the test now looks for `on…=` and `style=` only inside tags.
- The shared test database ran out of connections once two more test contexts existed; the test pools are four connections each (register item 95).

### Tests
Unit phase with Docker stopped (`docker desktop stop`, `mvn clean test`): **505** tests, all green (was 437). Full build with Docker (`mvn clean verify`): the same unit tests and **534** integration tests, all green, 1 skipped (the browser fixture) (was 464). New: `BoardRepositoryIsolationIT`, `BooksCheckIT`, `ApprovalSchemaIT`, `OrderApprovalIT`, `BoardFlowIT`, `BoardRoutesIT`, `BoardRenderIT`, `BoardExportIT`, `BoardExportCapIT`, `BoardActionLimitIT`, `BoardRateLimitsTest`, `BoardMappingsTest`, `NoticeAndMessagesTest`, `DisplayTest`, `PhoneMaskTest`, `MoneyFormatTest`, `CsvWriterTest`, `HtmxProvenanceTest`, and additions to the lints, the security property tests and the older render tests.

### Defenses weakened or traded off (design register items 76 to 95)
A third-party script in every page; the column grant on `orders.total_minor`, with an audit row that names the order but not the totals (a deviation from the design text); a total the seller states cannot be corrected yet; raw text shown to the seller with heuristic masking; display cleaning and CSV neutralisation alter what is shown or exported; the books check is cached five minutes; Flagged is partial until task 7; no automatic refresh; in-memory token-bucket limits; a helper sees names and amounts; scroll-snap needs CSS; no cancel or dismiss; English only; an npm developer tool outside the CI scan; eight redundant guards deleted; two clocks for "today"; one htmx session key; error answers in the notice area; small test pools.

### Not verified
- A real phone, other browsers, a screen reader, a slow network (above).
- The npm packages of the browser tool are not in the CI vulnerability scan; no Maven dependency was added, so the Maven scan was not re-run.
- The two-CPU Linux container run was not repeated for this stop.
- PostgreSQL 16.4 only.
- Concurrency at the HTTP level (two sellers clicking the same card) is covered by the service tests of task 6, not by a web test.
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.

## Task 7 - suspicion rules (stop 2)

The owner approved D1 to D13 of `.kiro/specs/chat-ledger/task-7-design-stop.md` with one change (D4: R4 `SHORT_OF_ROUND` dropped, so ten codes, removed from the tables, configuration, tests and mutation plan) and four clarifications. Work stays on `task-7-suspicion`; nothing was committed by me. The board's "partial" note was removed only after D9 was in place.

### The four clarifications
1. **Scope of D3, written down and tested.** A flag with no related order blocks every pair of that payment; an order flag blocks every pair of that order; a pair flag blocks only that pair. `FlagRepository.stateWithin` reads the three scopes; `FlagMatchingIT` has "pair flag on (P,A) does not block (P,B)", "payment-only flag blocks (P,B)", "pair flag on (P,B) blocks (P,B)", order flags on A and on B, and (added after the first mutation pass) "a pair flag on (P,A) does not block another payment Q on A".
2. **A pair finding and its suggestion commit together.** When `applyWithin` finds a pair flag under the lock, it writes the finding and the suggestion on the same connection and returns `DEGRADED_TO_SUGGESTION` with reason `FLAGGED`. Tested: the finding is still there after the degrade; an injected failure after the `FLAGGED` step leaves neither; the retry writes both once. The mutant that writes the findings in a transaction of their own that is rolled back is killed by three tests.
3. **A payment and its flags share one transaction, on purpose.** The payment rules (R1, R2, R6, R9, R10 on the payment) run inside the payment insert transaction, after its audit row; the order rule (R10 on the order) inside the order insert transaction. If a flag cannot be written, the payment (or the order and its posting) is not stored either, so no record exists without the flags it should carry. A retry is safe because ingestion is idempotent by (tenant, externalId): the retry is a first attempt and stores the record with its flags, and a replay of a stored one adds nothing; a flag is also unique per rule and subject. Tested in `FlagIngestionIT`.
4. **Task 9.** `tasks.md` under task 9: the demo seed must trigger at least R1, R5, R8 and R10/R11, so the demo shows real flags and the resolve button.

### What was built
- `suspicion` module: `FlagCode` (ten codes, each with one fixed sentence), `Finding`, the contexts, `SuspicionConfig` with bounds, `Suspicion` (payment, order and pair rules), one class per rule. No web dependency, no message text in a finding.
- V019: typed `payment_id` and `order_id` with foreign keys to the same tenant, shape and bound CHECKs, a unique key per rule and subject, a write-once trigger for every role (one resolution, no delete, no truncate), INSERT and `UPDATE (resolved_at, resolved_by)` for the application, a back-fill of the `DUPLICATE` and `UNVERIFIED` payments, an index for the sender rules. It refuses to run if `flag` already holds rows.
- `web/flags`: `FlagRepository`, `SuspicionWriter`, `SuspicionProperties` and `SuspicionWiring` (`app.suspicion.*`, `app.suspicion.disabled`), `FlagService` (resolve).
- Matching: `OrderFacts.flagged` and `PaymentFacts.flagged` (the subject's own flags) and the floor `NOT_FLAGGED`; the wall in `applyWithin`; the pair rules on every suggestion too.
- Board (D9, D13): Flagged from open flag rows only; a checked unverified payment goes to Review, a checked repeat to "Payments set aside or repeated"; the first open flag's sentence and "I checked this" on the card; every flag on the order and payment pages; the explainer names `NOT_FLAGGED` for a pair flag too.

### Findings while building
- **R6 could be evaded by sending at once.** Under READ COMMITTED sixteen payments from one phone, recorded concurrently, each saw only the committed ones: 4 were flagged instead of 13. `senderRecordsWithin` now takes `pg_advisory_xact_lock` on (tenant, phone hash) first. Found by the new test in `MatchingConcurrencyIT`, which now asserts exactly 13. Register item 104.
- **Two test fixtures hashed one phone differently**, so R7 fired in tests that were not about it. The fixtures share one key set (register item 109). The older concurrency tests now give each payer its own phone (item 110).
- **The explainer could not see a pair flag** (the re-score only has the two subjects' facts), so a pair-flagged suggestion did not say why it was not automatic. It now takes `pairFlagged`.

### What each route refuses
New: `POST /app/flags/{id}/resolve`.

| Route | Permission (who) | Also refuses |
|---|---|---|
| `POST /app/flags/{id}/resolve` | `RESOLVE_FLAG` (seller only; a helper gets 403 and sees no button) | without or with a wrong CSRF token (403, nothing written); a flag of another workspace or an unknown id (404, never 403, nothing changed); a bad UUID (400); 120 actions a minute per user (429); the service asks the database whether the user is an enabled seller, so a helper id passed to it is refused too; a second resolve changes nothing and answers "That flag was already marked as checked." |

Unchanged routes: no route refuses anything new because of a flag. Confirm, reject and manual match work on flagged pairs (D8, tested). Order and payment paste store the flags in the same transaction.

### Browser check: what I ran and what I did not
Ran the extended walk in headless Chrome (puppeteer-core, installed Chrome) at 390 by 844 against the real application on a throwaway database (`BrowserCheckFixtureIT`, `-Dbrowser.check=true`): **35 of 35 checks passed**. On top of task 8's walk: the flagged card shows the sentence and the resolve button; resolving with htmx answers in place and the Flagged chip drops to 0 out of band; without JavaScript a forward is flagged, the payment page lists the open flag with its button, and resolving returns to the same page with its notice. No console error or CSP violation in the whole walk.

Not run in a browser: a real phone or touch device; Safari, Firefox or an old Android browser; a slow network; a screen reader; resolving an order flag (integration tests only); the summary's "flagged now", the books and the download pages; a pair flag on a suggestion card.

### Mutation checks
Four workers, each on its own copy of the repository; each mutant applied, the named tests run, the files restored and touched. A kill counts only when the build reports a failing test.

First pass: 177 mutants, 152 killed, 24 survived, 1 invalid (a `while (false)` that does not compile; redone as `while (i < 0)`). Of the 24, 11 were guards no test could tell from their absence: they are deleted (register item 105). The other 13 exposed missing tests, now written: a resolver without a time refused by the check alone; a pair flag's order frozen by the trigger; a pair flag on (P,A) not blocking Q on A; repeats not counted in R6; more than 50 later payments not hiding an earlier burst; "flagged now" and the card after a resolve; the explainer's sentence for a payment-only and for an order-only flag; a flag of another tenant naming our records, run as the owner (card and detail); the last pair of digits swapped in R3.

Second pass, the whole set again on fresh copies, with two mutants added (R11 with a zero balance; R10's stored name dropped by the writer):

| Group | Mutants | Killed | Deleted as redundant |
|---|---|---|---|
| Rules R1 to R11 and name handling | 45 | 40 | 5 |
| Thresholds, bounds, runner, contexts, findings | 26 | 26 | 0 |
| Settings and wiring (`app.suspicion.*`) | 10 | 10 | 0 |
| Floor `NOT_FLAGGED` and explainer | 4 | 4 | 0 |
| V019 (grants, keys, checks, unique key, trigger, back-fill) | 31 | 30 | 1 |
| `FlagRepository` (scopes, tenant, advisory lock, sender records, resolve) | 12 | 12 | 0 |
| Writer and ingestion hooks | 11 | 8 | 3 |
| Matcher (wall, facts, degrade, same transaction) | 15 | 14 | 1 |
| Resolve service, route and shell | 10 | 9 | 1 |
| Board placement, counts, card, detail, templates | 15 | 15 | 0 |
| **Total** | **179** | **168** | **11** |

Nothing survives. No mutant was set aside as equivalent: the two tenant predicates I expected to stay unkillable (they are redundant with row-level security and the composite foreign keys) are killed by the owner-run test.

### Tests
Unit phase with Docker stopped (`docker desktop stop`, `mvn -o clean test`): **530** tests, all green (31 + 156 + 31 + 67 + 23 + 222; was 505). Full build with Docker (`mvn -o clean verify`): the same unit tests and **591** integration tests, all green, 1 skipped (the browser fixture) (was 534). New: `PaymentRulesTest`, `PairRulesTest` (jqwik), `OrderRuleTest`, `SuspicionTest`, `NoInputPatternsTest`, `SuspicionPropertiesTest`, `FlagMatchingIT`, `FlagIngestionIT`, `FlagRepositoryIsolationIT` (in the isolation registry), `FlagSchemaIT`, `FlagMigrationIT`, `FlagResolveIT`, and additions to the board, routes, isolation, rollback and concurrency tests.

### Deviations from the design stop
- `app.suspicion.disabled` (a list of codes) instead of one `enabled` key per rule (register item 108).
- The "customer existed" input of R10 on orders was removed: a new customer stores the parsed name, so the names always agree and the rule cannot fire; the guard was redundant (item 105).
- The explainer takes a `pairFlagged` argument (above).

### Defenses weakened or traded off (design register items 96 to 110)
The application may now write `flag` (INSERT and the resolution columns); the rules are heuristics; R6 and R10 need a full phone; global thresholds; any flag, even a checked one, costs a confirmation; the reason repeats the code's sentence; no re-run on history; two `EXISTS` per facts query; one sender's payments serialised by an advisory lock; eleven redundant guards deleted; tenant predicates kept although redundant; flags never deletable (conflicts with a future tenant-data deletion); the `disabled` list; shared fixture keys; distinct phones in the older concurrency tests.

### Not verified
- The cost of the two `EXISTS` on a large workspace (not measured).
- False-positive and false-negative rates of the rules on real traffic (no real data, by design).
- The two-CPU Linux container run was not repeated for this stop.
- The npm packages of the browser tool are still not in the CI vulnerability scan; jqwik was added to `suspicion` at the version the project already pins, so no new artifact entered the Maven scan.
- PostgreSQL 16.4 only.
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.

## Task 9 - simulators and demo tenant (stop 2)

The owner approved D1 to D21 of `.kiro/specs/chat-ledger/task-9-design-stop.md` with D3 and D4 changed (a pool of 30, bounds 10 to 50; a lease of 30 minutes, bounds 10 minutes to 4 hours) and four decisions added (D22 to D25, section 7 of the design stop). Work stays on `task-9-demo`; nothing was committed by me.

### What was built
- **Two switches, both off by default.** `app.demo.enabled` and the database's `deployment_mode` (V020). The demo operator's `demo_init` moves it from REGULAR to DEMO once, and only on a database with no regular workspace. The application does not start with the demo on against a regular database (`DemoStartupGuard`). In DEMO mode `provision_tenant` refuses; in REGULAR mode every demo function refuses.
- **Workspaces.**
  - `tenant.kind` is tied to the `demo-` slug and frozen with it by a trigger, for the owner too.
  - `demo_tenant` is the pool: forward-only states, never deleted, and its key includes the tenant's kind, so a row can name only a demo workspace.
  - Password sign-in and `provision_user` reach regular workspaces only.
- **Entry (D2, D22).** `GET /demo` shows one sentence and one button. `POST /demo` carries no field but the CSRF token:
  - `demo_claim` hands out the oldest fresh ready workspace (SKIP LOCKED);
  - the principal is read back from the seller row, as sign-in does;
  - the session id and CSRF token are renewed, a `DEMO_ENTERED` audit row is written, and the session ends with the lease;
  - a visitor already in a workspace goes back to it and takes no new lease.
- **The `demo` module (pure).**
  - Fictional names in three groups that never agree by initial or surname, and synthetic phones `+999 000`.
  - The catalog: eight items, each with an English and a French row.
  - The generator writes confirmations from the loaded template specs (their text, holes, zone and format), "now minus an age" between 0 and 4 days, cut to the minute.
  - The seven scenarios of requirement 7.2 and the seed of tasks.md.
- **Pages.** The scenarios page shows a simulated chat (bubbles) and a feed (SMS-style items), each scenario with "Send the order", "Send the payment" and "Run both". There is no text field. Every step goes through `OrderIngestionService` and `PaymentIngestionService` as the visitor's seller, with external ids `sim:<uuid>`. The board gets a "Demo, made-up data" chip and a "Scenarios" link, on demo workspaces only.
- **Demo input rules (D11, D12).** A demo workspace refuses parsed phones outside `+999`. It holds at most 300 orders and payments: the application answers with a sentence, and a database trigger refuses as well.
- **Upkeep (D6, D7, D24).**
  - `demo-init` and `demo-refresh [--pool 10..50] [--every 60s..3600s]` run like `provision-*`, with no web server. They use the demo operator's login (EXECUTE on the demo functions only, no table privilege) and the application login for the seed, through the real services.
  - A pass takes a session lock (`demo_refresh_lock`), retires what is due, and tops the pool up.
  - Retire disables the workspace and overwrites raw text, names, phone hashes, last four digits, line labels and delivery notes. It deletes nothing and leaves the ledger, the audit log and the flags as they were.
  - `docker/demo-entrypoint.sh` runs the loop as a second process and starts the web process without `DEMO_DB_*`.
- **Client address (D23).** `app.security.trusted-proxy-hops`: 0 to 2, default 0, checked at startup, named in the startup log. It is used by the sign-in limits and the demo limits.

### The owner's four additions
- **D22.** Tested by `DemoEntryIT`:
  - a second `GET /demo` or `POST /demo` from a visitor in a workspace redirects to `/app`, and the lease count does not move;
  - right after a retire the visitor may start again, without waiting for the status re-check.
- **D23.**
  - At 0 a spoofed header does not change the key (`ClientAddressTest`, `RateLimitFilterTest`, `DemoLimitsIT`: the sixth claim from one socket is refused whatever header it sends).
  - At 1, six judges behind the proxy are six keys, one judge is limited however it dresses its own part of the header, and the global limit holds (`DemoProxyIT`).
  - Task 13 has the line to set it for the host.
- **D24.** `demo-refresh --every` loops until stopped. Two runners never work at once (`DemoRefreshIT`). The entrypoint starts it as its own process (`DemoDeploymentFilesTest`). If the host cannot run a second process, the register (item 122) and task 13 say to use the host's scheduled job with the one-shot form.
- **D25.** Measured by `DemoSizeIT` on the test container: 129 rows and about 100 KB per seeded workspace, 236 rows and about 150 KB after a typical visit. Thirty days at the claim limits is about 6.5 GB (extrapolated, unverified). Realistic use is under 0.1 GB. It does not fit a 0.5 to 1 GB free tier at the limits; the operator drops the demo database before 80% (task 13).

### Scrub columns checked against their constraints
| Column | Constraint | Value written |
|---|---|---|
| `orders.raw_text` | NOT NULL, `chk_orders_raw_text` 1..4000, no control characters | "Removed when the demo ended." |
| `orders.delivery_note` | NULL or 1..300 | NULL |
| `order_line.label` | NOT NULL, `chk_order_line_label` 1..100 | "Removed" |
| `customer.display_name` | NOT NULL, `chk_customer_name` 1..100 | "Removed" |
| `customer.phone_hash`, `phone_last4` | NULL or a format; `chk_customer_phone_pair` both NULL together | both NULL |
| `payment.raw_text` | NOT NULL, `chk_payment_raw_text` 1..1000 | "Removed when the demo ended." |
| `payment.sender_name` | NULL or 2..40 | NULL |
| `payment.sender_phone_hash`, `sender_last4` | `chk_payment_sender` (a hash needs its last four; last four NULL or 4 digits) | both NULL |

`DemoSchemaIT` retires a seeded workspace and checks every column, and that the ledger, the audit log and the flags are byte for byte as before (an md5 over every row).

### Defects found while building
- **The guard tests caught two things.** `NoBackDoorTest` counted the inserts of tenants and users outside V013; V020's three replaced functions and `demo_create_tenant` now have their place in it. `SourceRulesTest` flagged a comment I had put inside a `prepareStatement(...)` argument, and a helper that passed SQL in a variable.
- **The seed's unverified payment first matched the wrong order.** Its amount (2,000) equalled the balance of the part-paid order, so it was also suggested there and raised R7 and R8 on that pair. It is now 2,800, a balance no other open order has.
- **`AbstractDemoWebIT` could not extend `AbstractWebIT`.** Spring registers the base class's `@DynamicPropertySource` last, so its database won. It is a sibling class now.
- **Too many test connections.** With six demo contexts and the demo pool, the shared test cluster ran out of connections and two 16-thread tests failed. Demo test pools now hold no idle connections. This is a test-only setting.
- **One finding in my own walk script, not the application.** The first browser runs looked at the wrong card (an order card with the same customer name) and used a date range above 366 days for the CSV.

### What each route refuses
| Route | Who | Also refuses |
|---|---|---|
| `GET /demo` | public, only on the demo deployment (404 elsewhere, for everyone) | more than 30 requests per 10 minutes from one address (429 before anything else); a visitor in a workspace is sent to `/app` |
| `POST /demo` | public, only on the demo deployment | missing or wrong CSRF token (403); the page limit; 5 claims an hour per address and 60 in total (429 with `Retry-After`, no workspace taken); an empty pool ("All demo workspaces are in use"); a leased seller that is disabled or not a seller ("busy", logged); a visitor already in a workspace goes back to it and takes no lease |
| `GET /app/demo` | `VIEW_BOARD`; a demo workspace only | any other workspace 404; anonymous is sent to `/demo` (htmx: 401 with `HX-Redirect: /demo`) |
| `POST /app/demo/scenarios/{scenario}/{step}` | `RECORD_ORDER` and `RECORD_PAYMENT`; a demo workspace only | CSRF (403); an unknown scenario or step 404; another workspace 404; 120 actions a minute per user and 20 steps per 10 minutes per workspace (429); the ingestion limit; a full workspace and a payment before its order (sentences); no text is read at all |
| `POST /app/orders`, `/app/payments` in a demo workspace | as before | a phone outside `+999` ("This demo takes made-up phone numbers only"); a full workspace ("This demo workspace is full. Start a new demo.") |
| `POST /login` | as before | a demo workspace is never found (generic failure) |

### Browser walk of the video story (390 by 844)
Ran in headless Chrome (the installed Google Chrome, puppeteer-core) against the real application as the demo deployment (`DemoBrowserFixtureIT`, `-Dbrowser.demo=true`, six seeded workspaces). **39 of 39 checks passed** on the final code. The steps follow the story's order:
- 0:00 the app's address lands on the demo page with one button and no text field; one click opens a board;
- 0:15 the seeded board: Matched 1, Review 1, Flagged 6, Unparsed 1, the fixed sentences and "I checked this";
- the scenarios page lists the seven, as simulated chat and confirmations, with no text field;
- 0:35 the happy path in two steps, matched by itself;
- 0:55 the underpayment confirmed from its card (htmx, in place), then "Part paid: 3,000 XAF of 5,000 XAF";
- 1:10 the overpayment flagged with its sentence and still confirmable;
- 1:30 the repeat flagged, "I checked this" in place, then with the payments set aside or repeated;
- 1:45 the screenshot payment flagged for its missing transaction id;
- 2:00 the wrong sender with both sentences, rejected from its card;
- 2:15 the late payment with both time sentences;
- 2:30 the books balance after the whole story, and the orders download is a CSV;
- opening `/demo` again returns to the same workspace;
- no console error or CSP violation in the whole walk;
- no sideways scroll, and every button and link at least 44 px tall, on `/demo` and `/app/demo`;
- without JavaScript: the button opens a board (another workspace), a scenario runs and returns to the board with its notice, and "I checked this" and Confirm are plain forms that return with their notices.

Thirteen screenshots are in `tools/browser-check/screenshots/demo-*.png` (not committed).

Not run in a browser:
- a real phone or touch device (the viewport is emulated);
- Safari, Firefox or an old Android browser;
- a slow network;
- a screen reader;
- the lease ending in a browser (integration tests only);
- a real proxy in front of the application (D23 is tested with headers set by the test);
- the video itself (task 14).

### Mutation checks
Four workers, each on its own copy, with mutated sibling modules built in the same reactor (never installed: the workers share the local repository). A kill counts only when the build reports a failing test.

- **First pass:** 147 mutants, 127 killed, 20 survived.
  - Three were guards no test could tell from their absence. They are deleted (register item 129): the Java "is it a demo workspace" check after `demo_claim`, `disabled_at` in `demo_claim`, and the unused `enabled` field of `DemoProperties`.
  - The other 17 exposed missing tests, now written:
    - the operator cannot change a workspace's seller, and a lease cannot end when it starts;
    - on a regular database a stray demo row is still refused by the mode (claim, catalog, retire);
    - a regular workspace has no row cap;
    - the configured lease is the one used;
    - a visitor whose workspace was just retired may start again at once;
    - a disabled or non-seller leased user is never signed in;
    - a step counts as an action;
    - a full workspace refuses a scenario and a seed;
    - a seed that writes too little is never ready;
    - the refresh refuses a pool out of bounds;
    - the provisioning command's demo sentence;
    - the generator refuses a currency with decimals;
    - the startup log line and the filter's address (two new unit tests).
- **Second pass:** the whole set again on fresh copies, 145 mutants. 142 were killed. Two needed a sharper or a retargeted test. One more redundant check was deleted: `tenant_kind` in the forward-only trigger, which its CHECK already keeps.

| Group | Mutants | Killed | Deleted as redundant |
|---|---|---|---|
| V020: mode, kind, pool states | 14 | 14 | 1 |
| V020: row cap | 5 | 5 | 0 |
| V020: sign-in and provisioning | 3 | 3 | 0 |
| V020: demo functions (mode, kind, bounds, lock, claim, due) | 21 | 21 | 1 |
| V020: retire (each overwrite, once, disable) | 9 | 9 | 0 |
| V020: grants | 2 | 2 | 0 |
| Ingestion: synthetic phones, row cap, intake | 8 | 8 | 0 |
| Client address and its bound and log | 11 | 11 | 0 |
| Limits: page, claim per address and total, step, action | 8 | 8 | 0 |
| Entry, session lease, home, entry point, sign-in link, startup guard | 22 | 22 | 2 |
| Scenarios page and steps | 13 | 13 | 0 |
| Generator and names | 11 | 11 | 0 |
| Seed and seeder | 5 | 5 | 0 |
| Refresh, commands, entrypoint | 12 | 12 | 0 |
| **Total (final set)** | **144** | **144** | **4** |

Nothing survives. I did not set any mutant aside as equivalent.

### Tests
- **Unit phase with Docker stopped** (`docker desktop stop`, `mvn -o clean test`): **565** tests, all green (31 + 156 + 31 + 67 + 23 + 10 + 247; was 530). The new `demo` module has 10.
- **Full build with Docker** (`mvn -o clean verify`): the same unit tests and **643** integration tests, all green, 2 skipped (the two browser fixtures; was 591).
- **One intermittent failure, not explained.** On one of the final full runs, `MatchingBooksPropertyIT` "a rematch repeated reaches a point where it changes nothing" failed once: a rematch kept suggesting for more passes than there were free payments. jqwik could not regenerate the falsified sample. Its previous seed then passed, and so did three more runs and the next full build. So it is not reproducible from its seed, which points at timing (the database clock against the test clock) rather than at the generated actions. Task 9 changes no matching code; on regular workspaces it adds only the demo checks in ingestion and the row-cap trigger. I have not found the cause, and I am not claiming the property is flake-free. It should be looked at before task 13.
- **New:**
  - unit: `DemoMaterialTest`, `DemoMessagesTest` (jqwik), `DemoCommandTest`, `DemoStartupGuardTest`, `DemoDeploymentFilesTest`, `ClientAddressTest`, `RateLimitFilterTest`;
  - integration: `DemoSchemaIT`, `DemoSeedIT`, `DemoScenarioIT`, `DemoRefreshIT`, `DemoSizeIT`, `DemoEntryIT`, `DemoRoutesIT`, `DemoLimitsIT`, `DemoProxyIT`, `DemoStepLimitIT`, `DemoActionLimitIT`, `DemoSwitchedOffIT`;
  - additions to `HandlerCoverageTest` (it now scans the demo-only controllers), `NoBackDoorTest`, `IsolationRegistry`, `ProvisioningIT`, `ProvisioningCommandTest`, `StartupGuardsTest`.

### Deviations from the design stop
- `/demo` is on the permit list always. Its handler exists only on the demo deployment, so it is a 404 elsewhere. The design said it would be off the list. The result is the same and is tested (`DemoSwitchedOffIT`).
- The scenarios page uses plain forms (post, then redirect), not htmx swaps, so a scenario lands on the board where its outcome is. Board actions keep htmx.
- The demo's own name list ("Ana Testa" and others) replaced the design stop's examples, so that no two people agree by initial (which would add identity points).
- `demo_tenant` has a `tenant_kind` column and the composite key `(tenant_id, tenant_kind)`. This is an extra database defense the design did not have.

### Defenses weakened or traded off (design register items 111 to 129)
- A public endpoint signs a visitor in without a password.
- The application may call `demo_claim`.
- Nine new definer functions.
- Two tables outside the per-table probe.
- A new operator role.
- Visitors act as sellers.
- Free text may carry real personal data until retired.
- Retired workspaces stay in the database.
- Ingestion knows demo workspaces.
- A trigger on every order and payment insert.
- The forwarded header is trusted when configured.
- The reset depends on a process outside the web application.
- Scripts in the visitor's session.
- The catalog JSON is built in Java.
- A row lock between provisioning and `demo_init`.
- A random password thrown away.
- The walk outside CI.
- Size estimated on a free tier.
- Four redundant guards deleted.

### Not verified
- The chosen host's limits: whether it can run a second process, its proxy hops and its database size (task 13).
- That `+999` is not an assigned country calling code (the repository's convention; not checked against the ITU list).
- `Africa/Douala` staying at +01:00 is pinned by a test against the JDK's tz data, not checked against an official source.
- The cost of the row-cap trigger on a large regular workspace (not measured).
- A real proxy, a real phone, other browsers, a screen reader, a slow network.
- PostgreSQL 16.4 only.
- The npm packages of the browser tool are still not in the CI vulnerability scan. No Maven dependency was added (the `demo` module uses jqwik at the pinned version).
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.

## Task 13 - deployment (stop 2)

The owner approved D1 to D19 of `.kiro/specs/chat-ledger/task-13-design-stop.md` with three changes: the image pipeline in three jobs with
every third-party action pinned by SHA, a runbook step to make the GHCR package public, and the tunnel token from a file if the pinned
cloudflared can read one (it can). Work stays on `task-13-deploy`; nothing was committed by me. The host is the owner's VPS, shared with
another project; I cannot reach it, so every step there is the owner's and **unverified** until its output is pasted back.

### What was built
- **The image** (`Dockerfile`, `.dockerignore`): Maven build stage, distroless `java21-debian12:nonroot` runtime (since replaced by
  `java21-debian13:nonroot`, see "The base moves to Debian 13" below), both by digest; uid
  65532; the jar root-owned 0444; no shell; an allowlist build context. 261 MB for amd64 (166 MB base, 69 MB jar).
- **Three commands before Spring**: `db-migrate` (Flyway only, the schema owner's login, refuses options, placeholders, a non-PostgreSQL
  URL and the application's login; a failure names its class only), `health-probe` (one loopback request; no redirect followed),
  and the existing `demo-*`.
- **Secrets as files**: `SecretFiles` for the commands (`NAME_FILE`; refuses both set, a non-regular file, a world-readable or
  -writable file, over 4 KiB, empty, more than one line); `file_env` in the role script (the same refusals); configtree for the web
  process; `deploy/make-secrets.sh` writes them (root, 0700 directories, 0440 files to the reader's group, never overwrites, never
  prints, the token one line of token characters).
- **The prod profile**: Flyway off, `app.security.hsts=always` (a new bounded setting, refused under dev), relative redirects, no
  forwarded-header rewriting, actuator on 127.0.0.1:8081, sized pools. `StartupGuards` refuses prod with dev, with Flyway on, or with
  forwarded headers.
- **The stack** (`deploy/compose.yaml`): db, migrate, web, refresh, tunnel; three networks of its own; every container hardened and
  bounded; no published port. Companions: `compose.test.yaml` (CI and local only: swaps the image), `hops-echo.yaml`, `env.example`.
- **Scripts**: `smoke.sh` (25 checks from outside), `check-host.sh` (read-only, on the VPS), `check-image.sh` (CI), `test-stack.sh`
  (the drill from nothing, CI and local).
- **CI**: every action pinned by SHA with its release in a comment; `persist-credentials: false`; `image-build` (build, check, drill,
  archive), `image-scan` (Trivy by digest on the archive, no write permission), `image-push` (main only, the same archive by id,
  `--password-stdin`, by commit, digest in the job summary; GitHub's artifact action and the Docker CLI only). `uptime.yml`:
  `permissions: {}`, two curl requests every 30 minutes.
- **D9**: every test and the local dev compose now run PostgreSQL 16.15 by the deployment's digest (`PostgresRelease`).
- **Docs**: `deploy/RUNBOOK.md` (host preparation, Cloudflare, first deploy, the public package, the hops, update, rollback, reset,
  checks, end of judging, never run); design.md "Deployment (task 13)" and register items 130 to 150; README demo URL and warm-up note.

### The owner's three changes, as built
- **Three jobs.** As above. How the pins were checked is in the design stop, section 9: each SHA is what the action's own repository's
  tag resolves to (`git ls-remote ... refs/tags/<tag>^{}`), and the release of that tag exists, not a draft or pre-release. Not
  verified: the code behind them (no review), and branch evidence for the checkout and setup-java tags (their tags are not on the
  default branch). `WorkflowFileTest` refuses an unpinned `uses:` or one without its release comment.
- **Public package.** RUNBOOK step 3.5, with the check: `docker logout ghcr.io`, then an anonymous pull of the digest from the VPS.
- **Token from a file.** cloudflared 2026.10.7 has `--token-file`. Tested with a read-only root, no capabilities and a made-up token:
  the file is read ("Provided Tunnel token is not valid"), and a missing file is reported. A real connection is unverified.

### Defects found while building
- `COPY --chmod=0444` also gave the created `/app` directory 0444, so the jar could not be opened ("Unable to access jarfile"). The
  mode is set in the build stage now; `check-image.sh` checks both.
- The Dockerfile's `dependency:go-offline || true` hid any resolution failure. It passes without the mask; the mask is gone.
- Tomcat turned redirects into absolute `http://` links behind the tunnel (`server.tomcat.use-relative-redirects=true`; found earlier
  in stop 2 by `ProdProfileIT`).
- **The drill caught a real deploy defect**: with the role script's directory at 0750, PostgreSQL (uid 70) could not list it, so no
  role was created and the database never became healthy. It is 0755 (no secret in it), in the drill, the runbook and the layout table.
- A non-executable initdb script is sourced into the image's entrypoint, where its `file_env` would replace the entrypoint's own. It is
  installed executable.
- The role script joined a multi-line password file into one value (`tr -d '\n'`). It refuses anything but one line now.
- `make-secrets.sh --tunnel-token` kept the first line of piped input and dropped the rest. It refuses more than one line now.
- `SourceRulesTest` caught `ComposeFileTest` reaching the test-database helper for the image name (it would have needed Docker in the
  unit phase). The name lives in `PostgresRelease`, a plain constant.
- `check-image.sh` checked only the health probe's exit code; an image without the command dispatch also exits 1 (Spring fails without
  a database). It checks the probe's exact output and `db-migrate`'s usage now; proved on a mutated image.
- My own mistakes in the scripts: the smoke script expected `/login` after sign-out (the demo deployment sends visitors to `/demo`);
  `check-host.sh` printed the firewall struct and had a route check that matched the egress bridge itself (removed);
  `test-stack.sh` counted exposed ports as published.

### What each check refuses
| Check | Refuses |
|---|---|
| `SecretFiles` (commands) | both `NAME` and `NAME_FILE`; a missing, non-regular (FIFO, directory) file; world-readable or -writable; over 4096 bytes; empty; more than one line; CR |
| `file_env` (role script) | both set; an unreadable file; empty; more than one line; CR |
| `db-migrate` | any option; a missing or blank value; a placeholder; a non-PostgreSQL URL; `MIGRATE_USER` equal to `DB_USER`; prints a failure's class only |
| `health-probe` | anything but a 200 whose body says UP; a redirect; a port outside 1..65535; extra arguments |
| `StartupGuards` | prod with dev; Flyway on (or unset) under prod; forwarded headers other than none under prod; `hsts=always` under dev |
| `make-secrets.sh` | not root; a relative or extra path; an existing directory; a token before the values, empty, over 4096, with other characters, more than one line, or when one exists; a failure leaves nothing behind |
| `ComposeFileTest` | a service not hardened or unbounded; swap; a published port, privilege, host namespace, device, socket, bind outside the project; another network or a shared name; a route out for anything but the tunnel; an unpinned image or a tag; a secret in `environment` or a wrong-uid secret file; `DEMO_DB_*`, `MIGRATE_*` or a dev profile on web; `JAVA_TOOL_OPTIONS`; a memory plan over 1,312 MiB; a missing log setting; the token on the command line or metrics on every address; a weakened echo or test override |
| `WorkflowFileTest` | an action not pinned by SHA or without its release; packages permission outside the push job; any write permission elsewhere; a push job that runs another image, action or downloader, or pushes off main, by tag, or without the id check; a scanner not pinned or not failing; a checkout that keeps its token; a secret beyond the two |
| `DockerfileTest` | an unpinned base; a secret-like ARG or ENV, a secret or ssh mount, ADD; a runtime stage with RUN or another user; a writable jar; a build context that is not an allowlist or that admits an environment file, a key or `.git` |
| `check-image.sh` | root or another uid; another entrypoint; no revision; a password, secret or token in the history; a working shell; a jar or `/app` not root's; a probe or `db-migrate` not dispatched before Spring |
| `smoke.sh` | the entry, the CSP by one byte, HSTS, a Server header from the origin, X-Powered-By, cached pages, a script outside `/assets/`, the actuator on the public port, `/health` saying more than UP, the cookie's flags, the board, a scenario, the books, sign-out, any absolute redirect |
| `check-host.sh` | the six log settings; size at or over 2.4 GB; a published port; a privileged or writable-root container; a socket mount; LLMNR listening; any secret file's owner, group or mode; any extra file under `secrets/` |

### Run locally, and only on the VPS
**Locally** (my machine, Apple silicon; the faithful runs inside a Docker-in-Docker host, a real Linux file system, so modes and uids
were enforced; Docker Desktop's own bind mounts show every file as root's and readable by all, so they prove nothing about modes):
- the image built for amd64 and arm64; `check-image.sh` on it and on eight broken images (all refused);
- `make-secrets.sh` and every refusal; the secret files read as uid 70 and 65532 under 0440 (a configtree file with a trailing newline
  accepted; a wrong password refused);
- PostgreSQL as uid 70 with a read-only root and no capabilities (no fallback needed); the six log settings;
- migrate (20 migrations), `demo-init`, a 30-workspace seed in 49 s at 0.5 CPU;
- web healthy 9 to 13 s after `up` (5.6 s JVM start at 1 CPU, native arm64; the VPS's amd64 time is unverified);
- the smoke script from the edge network: all 25 checks; and 4 defects (HSTS, absolute redirects, a Server header, the demo off) each
  refused by it;
- web, refresh and db have no route out and cannot reach 169.254.169.254; the edge network cannot reach db or port 8081; the egress
  network reaches out; the tunnel reads its token file and is refused by Cloudflare's parser;
- no secret value in `docker inspect`, the logs or the image history;
- memory: web 276 to 293 MiB of 640 under eight concurrent request loops and three smoke runs (NMT: 351 MiB committed; heap 192,
  metaspace 68, symbols 27, code 26), refresh 86 of 192, db 31 to 49 of 384;
- the reset at 2.4 GB, word for word; `check-host.sh` (with a DROP rule present and absent, and a loosened mode);
- `test-stack.sh` from nothing, end to end, on the final image.

**Only on the VPS** (unverified until pasted back): host preparation (LLMNR, the metadata rule and firewall backend, no collision,
room), the Cloudflare tunnel, ingress and zone settings, the public package and the anonymous pull, the first deploy, the hops
measurement, the smoke script through `https://ledger.nkap.dev`, `check-host.sh`, `docker stats` there, start times at 1 CPU on amd64,
one rollback rehearsal. CI's three image jobs have not run either: they run on the next push.

### Mutation checks
Three workers, each on its own copy of the repository; a kill counts only when the build reports a failing test.

- **Before the run**, reading the guards showed gaps, now closed: a FIFO secret (the regular-file check is what stops a reader from
  blocking forever; the first version of that test hung a worker, so the test attaches a writer), `DB_PASSWORD_FILE` in `DemoCommand`,
  the management address that `ProdProfileIT` set itself, the token-before-values message, cleanup after a failure half-way, and the
  role script joining lines (a code change). Two guards were deleted as redundant (register item 150).
- **First pass**: 117 mutants, 110 killed, 7 survived. Each exposed a missing or weak test: a blank migrate value; a redirect to an UP
  page; the probe's exit code; a null HSTS setting; the scanner pinned in only one of two steps; Flyway and the management port in the
  prod file, which `ProdProfileIT`'s own test properties mask (`ProdPropertiesTest` now reads the shipped files alone and runs the real
  guards on them).
- **Second pass**, on fresh copies: the five fixable survivors, the FIFO mutant and four new prod-file mutants. 10 of 10 killed. The two
  masked IT mutants are the same edits as two of these and are counted once.

| Group | Mutants | Killed |
|---|---|---|
| `SecretFiles` | 10 | 10 |
| `db-migrate` | 7 | 7 |
| `health-probe` | 6 | 6 |
| HSTS setting, `StartupGuards`, prod profile | 16 | 16 |
| `DemoCommand` secrets from files | 2 | 2 |
| `compose.yaml` and its companions | 29 | 29 |
| Workflows | 18 | 18 |
| `Dockerfile` and `.dockerignore` | 11 | 11 |
| `make-secrets.sh` | 16 | 16 |
| Role script `file_env` | 5 | 5 |
| **Total** | **120** | **120** |

Detector runs (scripts that check something outside the build): `check-image.sh` refused 8 of 8 broken images; `smoke.sh` refused 4 of
4 defects and passed the good build; `check-host.sh` refused a loosened mode and found a DROP rule when present. Nothing survives; no
mutant was set aside as equivalent.

### Tests
- **Unit phase with Docker stopped** (`docker desktop stop`, `mvn -o clean test`): **607** tests, all green (31 + 156 + 31 + 67 + 23
  + 10 + 289; was 565). The first run failed one: `SourceRulesTest` caught the unit test that reached the test-database helper (above).
- **Full build with Docker** (`docker desktop start`, `mvn -o clean verify`): the same unit tests and **653** integration tests, all
  green, 2 skipped (the two browser fixtures; was 643). The intermittent `MatchingBooksPropertyIT` failure of task 9 did not recur in
  this run; I did not look for its cause in this task.
- **New**:
  - unit: `ComposeFileTest`, `WorkflowFileTest`, `DockerfileTest`, `ProdPropertiesTest`, `SecretFilesTest`, `MigrateCommandTest`,
    `HealthProbeTest`; additions to `StartupGuardsTest`, `SecurityPropertiesTest`, `DemoCommandTest`;
  - integration: `ProdProfileIT`, `MakeSecretsScriptIT`, `InitScriptFilesIT`; five ITs moved to PostgreSQL 16.15.

### Deviations from the design stop
- Non-secret settings in the compose project's `.env`, not `config.env`: Compose reads it by itself, so no command can forget an
  `--env-file`. The rollback copy is `.env.<date>`.
- `secrets/` is 0700, not 0710: bind sources are resolved by the Docker daemon as root, so no container needs to enter it.
- The image is built, checked, drilled and scanned on every push and pull request, not only on `main`; only the push is main-only.
- Trivy is pinned by image digest (it runs from its own image, per the owner's change), not by an action SHA.
- Additions: the tunnel's metrics on its loopback and its remote diagnostics off; a fixed egress subnet for D17; `check-host.sh` checks
  the secret files; `start_interval` on the web health check; the role script directory 0755 and the script executable.
- The CI drill seeds 10 workspaces (speed); the VPS seeds 30.

### Defenses weakened or traded off (design register items 130 to 150)
- A shared kernel and Docker daemon.
- `sudo docker` is root.
- Hops at 0 until measured.
- No backup.
- Deploy downtime.
- A public, unsigned image.
- Cloudflare sees clear text; plain HTTP on the edge network.
- A second JVM for the health probe.
- Memory safe against current use, not limits; JVM ceilings near the limit.
- CPU overcommit.
- Best-effort uptime checks.
- Zone settings outside the repository.
- Three values on disk twice; the token shares the application's group.
- configtree checks no modes.
- Trusted Unix socket in the db container.
- Third-party code in CI trusted at its pin.
- The runtime JRE lags the JDK.
- The metadata address not blocked by default.
- The tunnel's egress and token.
- Verified on my machine, not the VPS.
- Two redundant guards deleted.

### Not verified
- Everything on the VPS and in the Cloudflare dashboard (listed above), and CI's three new jobs (they run on the next push).
- The action SHAs' code (not reviewed) and branch evidence for checkout and setup-java.
- That the image digests are the publishers' signed builds.
- The memory and start times on the VPS's amd64 CPU (measured on arm64 only).
- What Cloudflare and cloudflared write to `X-Forwarded-For` (the hops), and Cloudflare's free-plan limits for this account.
- That a `DOCKER-USER` rule persists across a reboot on this host.
- Est. Credits Used: not visible to me from inside the session; read it from the Kiro status bar.

### The first CI run: the image scan caught what the OWASP check did not
The PR's `Image, scan` job failed on fixable HIGH and CRITICAL findings: ten Debian packages in the pinned distroless base
(libbrotli1 CRITICAL, libexpat1 eight HIGH, liblcms2-2 HIGH), and five advisories in Jackson 3.1.5 (jackson-core and
jackson-databind), the version Spring Boot 4.1.1 manages. The owner's rule: fix, no ignore file for any finding with a fix.

- **Jackson.** `jackson-bom.version` is overridden to 3.1.8 in the root POM (the newest of the 3.1 line; 3.2 is a new minor). Every module
  now resolves 3.1.8. The rebuilt image has no Jackson finding (Trivy, locally). `JacksonVersionTest` reads the versions the libraries
  report: it fails without the override (3.1.5) and when databind alone is left behind (both mutants killed).
- **The OWASP check had passed on 3.1.5.** I re-ran it on a copy without the override, with the NVD data updated in the same session:
  it identified `jackson-core-3.1.5.jar` and `jackson-databind-3.1.5.jar` with the right CPEs and matched none of the five CVEs. With
  3.1.8 it passes too. So for these advisories the OWASP check was blind and Trivy was not. Why NVD had no match is a guess (version
  data not yet attached in NVD, while Trivy also reads the GitHub advisory database by Maven coordinates); **unverified**. Register
  item 154.
- **The base was not fixed, by the owner's rule.** `gcr.io/distroless/java21-debian12:nonroot` still points to the digest already pinned
  (`7e37784d…`), and Trivy finds the same ten packages in it: the publisher has not rebuilt it. I stopped there and did not add an
  exclusion. The image job keeps failing, so nothing is pushed (register item 152). Measured, to choose from:
  `java21-debian13:nonroot@sha256:d978eefd…` (Java 21.0.13, uid 65532) has no fixable HIGH or CRITICAL finding, and an image built on it
  passes `check-image.sh` (not drilled); `cc-debian12:nonroot` carries a libssl3 HIGH.
- **The digest report alone would not have caught this.** Every pin was CURRENT: the vulnerabilities appeared against pins that had
  not moved. So the weekly read-only workflow (`digests.yml`, `permissions: {}`, no action) does both: `deploy/check-digests.sh`
  reports moved tags, and `deploy/scan-runtime.sh` rescans the images that run on the VPS.
- **That rescan found two more.** CI had never scanned the PostgreSQL and cloudflared images. postgres 16.15-alpine: 26 HIGH/CRITICAL,
  all Go standard-library CVEs in its bundled `gosu`, which its entrypoint runs only when started as root (`db` starts as uid 70).
  cloudflared 2026.10.7: zlib1g twice and golang.org/x/net. Not re-pinned: pending the owner's decision (register item 153).
- **Runbook section 10** has the read-only checks, why the image job fails by design, and the steps to fix a base finding, a library
  finding, and the two other images.
- **Mutations for the new guards:** 15 of 15 killed. Jackson (override removed; databind behind), the digest report (always CURRENT; a
  stale pin passing; an unknown hidden; a file not read), the runtime scan (a finding passing; the application image not scanned; the
  scan never failing; another scanner; the compose pins missed), and the workflow (a write permission, an action, the scan job gone, a
  push).
- **Tests:** unit phase with Docker stopped **613** (31 + 156 + 31 + 67 + 23 + 10 + 295); full verify **653** integration tests, 2
  skipped. `mvn -P security-check verify -DskipTests`: BUILD SUCCESS with 3.1.8.
- **Not verified:** the CI jobs on the next push (the scan job will still fail on the base until it is decided); that 3.1.8 has no other
  advisory than the scanners know today; the reason for the NVD gap.

### The base moves to Debian 13, and the other three images
The owner chose option (a): the runtime base is `gcr.io/distroless/java21-debian13:nonroot@sha256:d978eefd…`, re-resolved today (the
same digest as when it was proposed). Behind it: Temurin 21.0.13+9 on amd64 and arm64, Debian 13.7, uid 65532. The build stage stays
`maven:3.9-eclipse-temurin-21` (Temurin 21.0.12.1). **Both are Java 21**; the runtime is now newer than the build JDK, where debian12's
21.0.10 was older.

**The full drill on the new base:**
- images built for amd64 (261 MB) and arm64 (271 MB); `check-image.sh` passes on both, including a new check that the runtime is
  Java 21 (a Java 17 twin is refused);
- Trivy on both archives, as CI runs it: no fixable HIGH or CRITICAL (Debian 0, `app.jar` 0), exit 0;
- arm64, natively, in Docker-in-Docker: from nothing, smoke 25 of 25, web healthy 12 s after `up` (Spring 5.9 s), 30 workspaces seeded
  in 53 s; web 290 MiB at rest, 305 MiB under eight request loops and three smoke runs (NMT 349 MiB committed), refresh 85, db 31;
  no route out for web, refresh and db; no published port;
- every process as its uid, read from `/proc` by the new `check-processes.sh`: web and refresh 65532, db 70, nothing as root; every
  root file system read-only, no capability, no-new-privileges; a root twin and a writable-root twin are refused;
- the secret files as uid 65532 sees them: `root:65532 0440`, readable by 65532, not by another uid;
- **amd64 under emulation: the first run failed.** The refresh seed was killed at its 192 MiB limit (`OOMKilled true`, peak about
  218 MiB); natively on arm64 the same seed peaks at about 119 MiB. My reading is the emulator's translation cache, counted in the
  container; unverified on a real amd64 CPU. With more memory for that run only (a throwaway override, not in the repository), the
  amd64 image passed the whole drill (37 checks; web 457 MiB at rest and a 21.9 s start, both emulated). The limits in `compose.yaml`
  are unchanged; CI's image job runs the drill natively on amd64 with the real limits, and the runbook checks `OOMKilled` after the
  first seed on the VPS (register 158).

**PostgreSQL, cloudflared, the hops echo.** The newest releases are the digests already pinned (`16-alpine` = 16.15-alpine; cloudflared
2026.10.7 = latest; whoami v1.12.0 = latest), so re-pinning changed nothing and the findings stand.
- **PostgreSQL: accepted, with evidence** (register 155). All 26 findings are the Go standard library in `/usr/local/bin/gosu`. The
  image calls gosu in three places, each `exec gosu postgres "$BASH_SOURCE" "$@"` directly under `if [ "$(id -u)" = '0' ]; then`
  (docker-entrypoint.sh line 341, and the two initdb helpers); gosu is 0755 and the image has no setuid file; db starts as 70:70,
  read-only, with no capabilities and no-new-privileges. `deploy/check-gosu.sh` checks all of it on the pinned image and traces the
  image's own entrypoint with `bash -x` under those settings: gosu is never executed, and the server comes up as uid 70; started as
  root, the same trace does show gosu (the control). `CheckGosuIT` runs it and shows each check failing on a broken compose file or
  image (db as root, a writable root, an unguarded call, a fourth call, a setuid gosu, no call at all). `scan-runtime.sh` leaves out
  that one file only in a run where the evidence passes: re-checked every week, and the acceptance ends when it fails.
- **cloudflared: not accepted, pending the owner** (register 156). zlib1g twice (its Debian 13 base; the binary is static, so libz is
  never loaded) and golang.org/x/net v0.61.0 (an `x/net/html` DoS; that package is not linked into the binary). Checked on both
  architectures.
- **whoami: not accepted, pending the owner** (register 157). 17 findings in its binary; it runs only for the hops measurement.
- **The weekly scan covers four images** (application, PostgreSQL, cloudflared, hops echo). The image job's gate in `ci.yml` is
  unchanged. Until 156 and 157 are decided, the weekly scan fails, which is its notification.

**Mutations for this round:** 18 of 18 killed. The base in `DockerfileTest` (back to debian12; a Java 17 runtime; a JDK 17 build); the
gosu acceptance in `scan-runtime.sh` (skipped without evidence; skipped everywhere; broadened to a directory; the echo or the
application image left out; any application image accepted); and every check of `check-gosu.sh` (each check made to pass always, the
trace run as root). Detector runs: a Java 17 twin (`check-image.sh`), and a root twin and a writable-root twin (`check-processes.sh`).
Two checks were deleted as redundant (register 159).

**Not verified:** the amd64 memory and start time on a real CPU (CI's next run and the VPS); CI's three image jobs and the weekly
workflow on GitHub; whether Debian 13's newer system libraries change anything at run time beyond the drill.

### cloudflared and the hops echo accepted, refresh at 256 MiB
The owner's decisions: cloudflared accepted on the evidence, whoami for the one hops measurement only, refresh raised to 256 MiB.

- **cloudflared (register 156).** `deploy/check-cloudflared.sh` proves it from the pinned image, on both architectures: the entrypoint
  is `cloudflared` and compose does not override it; the binary has no program interpreter, so no dynamic loader runs and libz is
  never loaded; it keeps its symbol table (about 41,000 symbols); and no `golang.org/x/net/html` symbol is in it. `CheckCloudflaredIT`
  builds twins of the pinned image with a small Go program in place of the binary (a local module stands in for `x/net`, so no network
  is needed) and shows each check refusing its twin: dynamically linked, stripped, with the html package, another entrypoint, and a
  compose file that overrides it; a static twin without the package passes. `scan-runtime.sh` accepts, and only in a run where that
  evidence passes, any zlib1g finding and CVE-2026-78661 by id; another x/net finding still fails, because other x/net packages are
  linked. No ignore file.
- **whoami (register 157).** Time-boxed: pinned by digest, hardened like every service, on the `edge` network only (no route out),
  never restarted. The runbook now creates the DNS record and tunnel route for `ledger.nkap.dev` at the moment of the measurement,
  pointed at the echo; removes the echo right after; and routes the hostname to web only then. `check-host.sh` fails whenever a
  hops-echo container exists (a detector run with a running echo twin: FAIL; removed: PASS). The weekly scan lists its findings.
- **refresh 256 MiB (register 158).** `-Xmx96m` kept. 1,376 MiB (1.34 GiB) while serving: at the other project's current use that leaves
  about 1.36 GiB free on the 3.7 GiB host; with both projects at their limits, 3,680 MiB, about 110 MiB for the host itself. The amd64
  image passed the drill under emulation with the real limits this time (37 checks, refresh 136 MiB of 256 at rest, web 450 of 640; a
  10-workspace seed). The out-of-memory check after the first seed on the VPS stays in the runbook (step 3.11).
- **Mutations:** 16 of 16 killed: the cloudflared acceptance in `scan-runtime.sh` (without evidence; any x/net finding; any OS package;
  an uncovered finding passing; an unreadable image passing), the echo listing (unreadable passing), the compose limits and the echo's
  hardening, and each check of `check-cloudflared.sh`. Two checks were deleted as redundant (register 160): the `CGO_ENABLED` flag and
  the `NEEDED` count, which no twin could tell from the interpreter check.
- **Not verified:** a real amd64 CPU (CI's next run, then the VPS); the 30-workspace seed on amd64 at 256 MiB (only a 10-workspace seed
  ran there); the Cloudflare behaviour of creating the DNS record with the public hostname, which is how the dashboard works as far
  as I know, not something I could try.
- **Found, not fixed: the task 9 intermittent property failure is now reproducible.** The final `mvn -o clean verify` ran 665
  integration tests: 664 passed, 2 skipped, and `MatchingBooksPropertyIT` "a rematch repeated reaches a point where it changes
  nothing" failed ("a rematch kept changing things for more passes than there are free payments"). jqwik replays a stored failure
  first, so every rerun here fails with seed `3454080274824834196` (kept in the git-ignored `web/.jqwik-database`). The same seed fails
  on the owner's commit `641436d`, checked in a separate worktree, and that commit passes with fresh random seeds, so this round's
  changes do not cause it (and the Jackson override alone does not change it). It is a defect in the rematch logic or in the property,
  outside task 13; reproduce with `@Property(seed = "3454080274824834196")` on that property. The unit phase with Docker stopped is
  green (616).
