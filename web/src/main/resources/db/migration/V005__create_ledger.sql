-- Migration V005: double-entry ledger tables.
-- ledger_entry rows may NEVER be updated or deleted by the application role.
-- The DB-level permission is granted in V006 to the restricted app role.
-- Corrections go through reversal transactions (new rows, not edits).
-- Requirement: 4.1 (balanced entries), 4.2 (no edit or delete).

CREATE TABLE account (
    id        UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID  NOT NULL REFERENCES tenant(id),
    code      TEXT  NOT NULL,
    name      TEXT  NOT NULL,
    type      TEXT  NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),

    CONSTRAINT uq_account_code_per_tenant UNIQUE (tenant_id, code)
);

CREATE TABLE ledger_txn (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenant(id),
    kind       TEXT        NOT NULL,   -- e.g. ORDER_ACCEPTED, PAYMENT_MATCHED
    ref_type   TEXT        NOT NULL,   -- e.g. ORDER, PAYMENT, MATCH
    ref_id     UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entry (
    id           UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_id       UUID    NOT NULL REFERENCES ledger_txn(id),
    account_id   UUID    NOT NULL REFERENCES account(id),
    debit_minor  BIGINT  NOT NULL DEFAULT 0 CHECK (debit_minor  >= 0),
    credit_minor BIGINT  NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),

    -- Exactly one of the two sides must be positive.
    CONSTRAINT chk_entry_one_side CHECK (
        (debit_minor > 0 AND credit_minor = 0) OR
        (credit_minor > 0 AND debit_minor = 0)
    )
);

CREATE INDEX idx_ledger_txn_tenant   ON ledger_txn(tenant_id);
CREATE INDEX idx_ledger_entry_txn    ON ledger_entry(txn_id);
CREATE INDEX idx_ledger_entry_acct   ON ledger_entry(account_id);
