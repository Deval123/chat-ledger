-- Migration V003: customers.
-- Phone numbers are stored hashed (per-tenant salt) plus the last 4 digits for display.
-- The full number is never stored in plain text.
-- Requirement: 9 (Security — phone numbers stored hashed), 1.4 (masked in UI).

CREATE TABLE customer (
    id           UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID  NOT NULL REFERENCES tenant(id),
    display_name TEXT  NOT NULL,
    phone_hash   TEXT,          -- BCrypt/Argon2 hash with per-tenant salt
    phone_last4  CHAR(4),       -- last 4 digits shown in the UI
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_customer_phone_per_tenant UNIQUE (tenant_id, phone_hash)
);

CREATE INDEX idx_customer_tenant ON customer(tenant_id);
