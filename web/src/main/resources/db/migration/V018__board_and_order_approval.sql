-- Migration V018: the board, the daily summary, and the way out of NEEDS_REVIEW (task 8).
--
--   1. tenant.timezone: a "day" for the daily summary is midnight to midnight in the tenant's own zone.
--   2. Three indexes for the board and the summary.
--   3. An order that needs review gets its total from the seller and becomes payable. That is the only time a total may change, and the
--      order must then have its ORDER_ACCEPTED posting by commit:
--        - the application role may UPDATE orders.total_minor (column-limited, like the match amounts of V016);
--        - the status trigger allows NEEDS_REVIEW -> PENDING_PAYMENT and no other move out of NEEDS_REVIEW;
--        - a trigger refuses ANY change of total_minor outside that one move, for every role (the owner included);
--        - a deferred constraint trigger requires the posting at commit.
--      All the checks are in the database, so a bug in the application cannot make an order payable without its receivable, or change
--      a total in place.
--
-- Requirements: 6, 4.1, 4.4, 9.1. Task 8.

-- ── 1. Time zone ───────────────────────────────────────────────────────────
ALTER TABLE tenant ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC';
ALTER TABLE tenant ADD CONSTRAINT chk_tenant_timezone
    CHECK (char_length(timezone) BETWEEN 1 AND 64 AND timezone ~ '^[A-Za-z_]+(/[A-Za-z0-9_+-]+){0,2}$');

-- ── 2. Indexes ─────────────────────────────────────────────────────────────
-- Orders recorded in a day; payments paid in a day; the open flags of a subject.
CREATE INDEX idx_orders_tenant_created ON orders (tenant_id, created_at DESC, id DESC);
CREATE INDEX idx_payment_tenant_paid ON payment (tenant_id, paid_at) WHERE paid_at IS NOT NULL;
CREATE INDEX idx_flag_open_subject ON flag (tenant_id, subject_id) WHERE resolved_at IS NULL;

-- ── 3a. Order status: add the one move out of NEEDS_REVIEW ──────────────────
CREATE OR REPLACE FUNCTION orders_status_transition()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID')
       AND NEW.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID') THEN
        RETURN NEW;
    END IF;
    -- The seller states the total of an order that needs review, and it becomes payable (and is posted: see below).
    IF OLD.status = 'NEEDS_REVIEW' AND NEW.status = 'PENDING_PAYMENT' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'order status change % to % is not allowed', OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
END;
$$;

-- ── 3b. The total changes only in that move ────────────────────────────────
GRANT UPDATE (total_minor) ON orders TO chatledger_app;

CREATE OR REPLACE FUNCTION orders_total_frozen()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT (OLD.status = 'NEEDS_REVIEW' AND NEW.status = 'PENDING_PAYMENT') THEN
        RAISE EXCEPTION 'the total of order % can only be set when it leaves NEEDS_REVIEW', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_orders_total_frozen
    BEFORE UPDATE OF total_minor ON orders
    FOR EACH ROW
    WHEN (NEW.total_minor IS DISTINCT FROM OLD.total_minor)
    EXECUTE FUNCTION orders_total_frozen();

-- ── 3c. An order that left NEEDS_REVIEW has its ORDER_ACCEPTED posting at commit ──
CREATE OR REPLACE FUNCTION orders_assert_posted()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger_txn t
                    WHERE t.tenant_id = NEW.tenant_id AND t.kind = 'ORDER_ACCEPTED' AND t.ref_id = NEW.id) THEN
        RAISE EXCEPTION 'order % became payable without its ORDER_ACCEPTED posting', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_orders_assert_posted
    AFTER UPDATE OF status ON orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status = 'NEEDS_REVIEW' AND NEW.status = 'PENDING_PAYMENT')
    EXECUTE FUNCTION orders_assert_posted();
