# chat-ledger

Match WhatsApp orders to mobile money payments, flag suspicious confirmations,
and keep an auditable double-entry record.

Built for Kiroween 2026 (Frankenstein category).
See `.kiro/specs/chat-ledger/` for requirements, design, and tasks.

License: Apache-2.0

---

## Quick start (local development)

**Prerequisites:** Java 21, Maven 3.9+, Docker.

```bash
# 1. Copy the environment template and fill in the placeholders
cp .env.example .env
# Edit .env — replace every <replace-…> placeholder with real values

# 2. Start PostgreSQL
docker compose up -d

# 3. Build and run all module tests
mvn verify

# 4. Start the application
mvn -pl web spring-boot:run
# Open http://localhost:8080/health
```

## Module structure

| Module | Responsibility |
|---|---|
| `ledger-core` | Double-entry accounts, immutable entries, balance invariant |
| `extraction` | Order parser, payment templates, optional AI extractor |
| `ingestion` | HTTP endpoints, HMAC verification, idempotency, rate limits |
| `matching` | Scoring engine, match thresholds, overrides, ledger postings |
| `suspicion` | Advisory rule set: duplicate TX, name mismatch, time anomaly … |
| `web` | Board UI (Thymeleaf + HTMX), simulators, CSV export, auth |

## CI

GitHub Actions runs on every push:

1. `mvn verify` — builds all modules and runs tests.
2. `mvn -P security-check verify -DskipTests` — OWASP dependency vulnerability scan (fails on CVSS ≥ 7).

## Security notes

- Secrets are loaded from environment variables only. See `.env.example`.
- Phone numbers are stored hashed with a per-tenant salt plus the last 4 digits.
- Ledger entries are append-only at the database level; corrections use reversals.
- See `.kiro/steering/security.md` for the full security policy.

## Demo

The demo tenant uses synthetic data only. No real phone numbers, names, or
operator wording appear anywhere in the repository.

## Creating a workspace and users (operator only)

There is no sign-up page. An operator creates a workspace and its first seller, then more users, with two
one-shot commands. They connect as a separate database login (`PROVISION_DB_*` in `.env.example`) that can
only run the two provisioning functions. The new user's password is typed at a prompt or piped on standard
input; it is never accepted as an argument or an environment variable.

```
java -jar web/target/web-*.jar provision-tenant --workspace river-shop --name "River Shop" --email seller@example.test
java -jar web/target/web-*.jar provision-user   --workspace river-shop --email helper@example.test --role HELPER
```

A database created before the provisioner role existed needs the role and the grants once, as the database
superuser (migration V013 already created the functions):

```
CREATE ROLE chatledger_provisioner NOLOGIN;
GRANT EXECUTE ON FUNCTION provision_tenant(text, text, text, text), provision_user(text, text, text, text)
    TO chatledger_provisioner;
-- and a login role that is a member of it, with a password you generate
```

## Security in short

- Every tenant table has row-level security and every query is scoped by tenant; ledger and audit rows can never be updated or deleted.
- Passwords are Argon2id. Sign-in failures all look the same, and sign-in is throttled per account, per address and per
  concurrent password check. There is no sign-up page: an operator provisions workspaces and users.
- Sessions use a `__Host-` cookie (Secure, HttpOnly, SameSite=Strict), expire after 30 minutes idle and 12 hours in total, and
  every POST needs a CSRF token (htmx pages carry it as a `hx-headers` attribute on `<body>`).
- A strict content security policy is sent with every response. Templates may not contain inline scripts, styles, event handlers
  or unescaped output; a test reads every template.
- Request bodies are limited (4 KiB login, 16 KiB JSON, 64 KiB otherwise) and must be clean UTF-8 without control characters.
- Secrets come from environment variables only. `mvn -Psecurity-check verify` checks dependencies for known vulnerabilities.
- Not yet in place: TLS and trusted-proxy settings (deployment, task 13), so HSTS and per-address limits behind a proxy need
  that step.
- Order text from chat messages is untrusted data: it is re-validated, parsed by a strict deterministic parser (no pattern is built
  from it), stored with bound parameters only, never logged, and escaped on output. Phone numbers are stored only as a keyed hash plus
  the last four digits.
