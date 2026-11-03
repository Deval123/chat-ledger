-- Migration V001: core tenant table.
-- Every table in the application carries a tenant_id column so that
-- the application layer can enforce row-level isolation.
-- Requirement: 4.3 (Ledger data separate per tenant), 9.1 (isolate every query by tenant).

CREATE TABLE tenant (
    id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name     TEXT        NOT NULL,
    currency CHAR(3)     NOT NULL DEFAULT 'XAF',
    scale    SMALLINT    NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
