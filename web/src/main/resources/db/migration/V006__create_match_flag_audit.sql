-- Migration V006: match, suspicion flag, and audit log tables.
-- Requirement: 3 (matching), 5 (suspicion flags), 9 (audit log).

CREATE TABLE match (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID        NOT NULL REFERENCES orders(id),
    payment_id  UUID        NOT NULL REFERENCES payment(id),
    amount_minor BIGINT     NOT NULL,
    score       SMALLINT    NOT NULL,
    reasons_json TEXT       NOT NULL DEFAULT '[]',
    state       TEXT        NOT NULL CHECK (state IN (
                    'AUTO','SUGGESTED','CONFIRMED','REJECTED','REVERSED')),
    decided_by  UUID        REFERENCES app_user(id),
    decided_at  TIMESTAMPTZ
);

CREATE TABLE flag (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenant(id),
    subject_type TEXT        NOT NULL,
    subject_id   UUID        NOT NULL,
    code         TEXT        NOT NULL,
    reason       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);

CREATE TABLE audit_log (
    id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID        NOT NULL REFERENCES tenant(id),
    actor     UUID        REFERENCES app_user(id),
    action    TEXT        NOT NULL,
    subject   TEXT        NOT NULL,
    at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_match_order   ON match(order_id);
CREATE INDEX idx_match_payment ON match(payment_id);
CREATE INDEX idx_flag_tenant   ON flag(tenant_id);
CREATE INDEX idx_audit_tenant  ON audit_log(tenant_id);
