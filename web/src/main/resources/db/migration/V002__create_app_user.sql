-- Migration V002: application users.
-- Passwords are hashed by the application layer (modern algorithm, never plain text).
-- Requirement: 9.1 (authentication), 3 (tenancy).

CREATE TABLE app_user (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenant(id),
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL CHECK (role IN ('SELLER','HELPER')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_user_email_per_tenant UNIQUE (tenant_id, email)
);

CREATE INDEX idx_app_user_tenant ON app_user(tenant_id);
