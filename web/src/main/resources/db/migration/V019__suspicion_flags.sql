-- Migration V019: suspicion flags become writable, typed, idempotent and write-once (task 7).
--
--   1. The polymorphic subject_id is replaced by typed payment_id and order_id with composite foreign keys to the same tenant, so the
--      database refuses a flag about another tenant's record or a record that does not exist. A payment flag may name one order: then it
--      is about that pair only.
--   2. CHECKs tie each code to its shape (payment, pair, order), bound the reason, and keep the resolution consistent.
--   3. One flag per rule and subject: a unique key, written with INSERT ... ON CONFLICT DO NOTHING, so a rule can run again safely.
--   4. A flag is write-once except for its resolution, for every role including the owner: every column is frozen, resolved_at goes
--      from NULL to a value once and never back, and a flag cannot be deleted or truncated.
--   5. The application role may INSERT and may UPDATE only (resolved_at, resolved_by).
--   6. Back-fill: the payments that are already DUPLICATE or UNVERIFIED get their flag, so the board can show flags from rows only.
--   7. An index for the sender rules (R6, R10).
--
-- The table was SELECT-only for the application until now, so nothing has written it. This migration refuses to run if it holds rows,
-- rather than guessing how to map an untyped subject.
--
-- Requirements: 5, 9.1. Task 7.

-- ── 0. Refuse an unexpected state ──────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM flag) THEN
        RAISE EXCEPTION 'V019 expects the flag table to be empty: it was never writable by the application';
    END IF;
END;
$$;

-- ── 1. Typed subject ───────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_flag_open_subject;
ALTER TABLE flag DROP COLUMN subject_id;
ALTER TABLE flag ADD COLUMN payment_id  UUID;
ALTER TABLE flag ADD COLUMN order_id    UUID;
ALTER TABLE flag ADD COLUMN resolved_by UUID;

ALTER TABLE flag ADD CONSTRAINT fk_flag_payment_same_tenant
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payment (id, tenant_id);
ALTER TABLE flag ADD CONSTRAINT fk_flag_order_same_tenant
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id);
ALTER TABLE flag ADD CONSTRAINT fk_flag_resolver_same_tenant
    FOREIGN KEY (resolved_by, tenant_id) REFERENCES app_user (id, tenant_id);

-- ── 2. Shapes ──────────────────────────────────────────────────────────────
ALTER TABLE flag ADD CONSTRAINT chk_flag_subject CHECK (
       (subject_type = 'PAYMENT' AND payment_id IS NOT NULL)
    OR (subject_type = 'ORDER' AND order_id IS NOT NULL AND payment_id IS NULL)
);
-- Each code has one shape: about a payment, about a pair (a payment and the order it was matched or proposed against), or about an order.
ALTER TABLE flag ADD CONSTRAINT chk_flag_code_shape CHECK (
       (code IN ('DUPLICATE_TX', 'NO_PROVIDER_ID', 'SENDER_BURST', 'PAID_OUTSIDE_USUAL_WINDOW')
            AND subject_type = 'PAYMENT' AND order_id IS NULL)
    OR (code IN ('AMOUNT_NEAR_MISS', 'PAID_BEFORE_ORDER', 'PHONE_MISMATCH', 'NAME_MISMATCH', 'OVERPAYMENT')
            AND subject_type = 'PAYMENT' AND order_id IS NOT NULL)
    OR (code = 'SENDER_ALIAS' AND (subject_type = 'ORDER' OR order_id IS NULL))
);
ALTER TABLE flag ADD CONSTRAINT chk_flag_reason CHECK (char_length(reason) BETWEEN 1 AND 300);
ALTER TABLE flag ADD CONSTRAINT chk_flag_resolution CHECK (
    (resolved_at IS NULL) = (resolved_by IS NULL) AND (resolved_at IS NULL OR resolved_at >= created_at)
);

-- ── 3. One flag per rule and subject ───────────────────────────────────────
-- (subject_type is not in the key: chk_flag_subject makes it follow from whether payment_id is set.)
CREATE UNIQUE INDEX uq_flag_rule_subject ON flag (tenant_id, code,
    COALESCE(payment_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(order_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Every flag of a payment or of an order, open or resolved (the matcher counts resolved ones too: task 7, D3), and the open ones (the board).
CREATE INDEX idx_flag_payment      ON flag (tenant_id, payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX idx_flag_order        ON flag (tenant_id, order_id)   WHERE order_id IS NOT NULL;
CREATE INDEX idx_flag_payment_open ON flag (tenant_id, payment_id) WHERE payment_id IS NOT NULL AND resolved_at IS NULL;
CREATE INDEX idx_flag_order_open   ON flag (tenant_id, order_id)   WHERE order_id IS NOT NULL AND resolved_at IS NULL;

-- ── 4. Write-once except for the resolution ────────────────────────────────
CREATE OR REPLACE FUNCTION flag_write_once()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'flags cannot be deleted or truncated' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.resolved_at IS NOT NULL THEN
        RAISE EXCEPTION 'flag % is already resolved', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.resolved_at IS NULL THEN
        RAISE EXCEPTION 'an update of flag % must resolve it', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.subject_type, NEW.payment_id, NEW.order_id, NEW.code, NEW.reason, NEW.created_at)
       IS DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.subject_type, OLD.payment_id, OLD.order_id, OLD.code, OLD.reason, OLD.created_at) THEN
        RAISE EXCEPTION 'only the resolution of flag % may change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_flag_write_once
    BEFORE UPDATE OR DELETE ON flag
    FOR EACH ROW EXECUTE FUNCTION flag_write_once();
CREATE TRIGGER trg_flag_no_truncate
    BEFORE TRUNCATE ON flag
    FOR EACH STATEMENT EXECUTE FUNCTION flag_write_once();

-- ── 5. Privileges ──────────────────────────────────────────────────────────
GRANT INSERT ON flag TO chatledger_app;
GRANT UPDATE (resolved_at, resolved_by) ON flag TO chatledger_app;

-- ── 6. Back-fill ───────────────────────────────────────────────────────────
-- The sentences are the fixed reasons of FlagCode (a test compares them).
INSERT INTO flag (id, tenant_id, subject_type, payment_id, code, reason, created_at)
SELECT gen_random_uuid(), p.tenant_id, 'PAYMENT', p.id, 'DUPLICATE_TX',
       'This transaction id was already recorded. The same confirmation may have been sent twice.', p.created_at
FROM payment p WHERE p.status = 'DUPLICATE';
INSERT INTO flag (id, tenant_id, subject_type, payment_id, code, reason, created_at)
SELECT gen_random_uuid(), p.tenant_id, 'PAYMENT', p.id, 'NO_PROVIDER_ID',
       'This confirmation has no transaction id. Check your provider balance before you hand over the goods.', p.created_at
FROM payment p WHERE p.status = 'UNVERIFIED';

-- ── 7. The sender rules read the payments of one sender phone, most recent first ──
CREATE INDEX idx_payment_sender_paid ON payment (tenant_id, sender_phone_hash, paid_at DESC) WHERE sender_phone_hash IS NOT NULL;
