-- Migration V004: orders and payments.
-- Amounts are integers in minor units; no NUMERIC or FLOAT columns for money.
-- external_id is unique per tenant to enforce idempotent ingestion.
-- Requirement: 1, 2, 4.4 (integer minor units).

CREATE TABLE orders (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenant(id),
    external_id  TEXT        NOT NULL,
    customer_id  UUID        REFERENCES customer(id),
    total_minor  BIGINT      NOT NULL,
    currency     CHAR(3)     NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN (
                     'PENDING_PAYMENT','PARTIALLY_PAID','PAID',
                     'NEEDS_REVIEW','UNPARSED','CANCELLED')),
    raw_text     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_order_external_per_tenant UNIQUE (tenant_id, external_id)
);

CREATE TABLE order_line (
    id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID    NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    label       TEXT    NOT NULL,
    qty         INT     NOT NULL CHECK (qty > 0),
    unit_minor  BIGINT  NOT NULL
);

CREATE TABLE payment (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenant(id),
    provider     TEXT        NOT NULL,
    tx_id        TEXT,                   -- null for UNVERIFIED payments
    amount_minor BIGINT      NOT NULL,
    currency     CHAR(3)     NOT NULL,
    sender_name  TEXT,
    sender_last4 CHAR(4),
    paid_at      TIMESTAMPTZ,
    status       TEXT        NOT NULL CHECK (status IN (
                     'RECEIVED','MATCHED','UNMATCHED',
                     'DUPLICATE','UNVERIFIED','UNPARSED')),
    raw_text     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A transaction id must be unique per tenant+provider, but only when present.
    CONSTRAINT uq_payment_txid_per_tenant_provider UNIQUE (tenant_id, provider, tx_id)
);

CREATE INDEX idx_orders_tenant  ON orders(tenant_id);
CREATE INDEX idx_payment_tenant ON payment(tenant_id);
