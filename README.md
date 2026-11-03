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
