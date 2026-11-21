-- V021: cancel an order, dismiss a record (task 12b, first pull request; design: task-12-design-stop.md, section 5, D8, D10, D12).
--
-- Cancel: a payable order with no applied amount becomes CANCELLED, and the reversal of its ORDER_ACCEPTED posting commits with it.
-- Dismiss: an order or a payment that never reached the books (UNPARSED, or an order in NEEDS_REVIEW) leaves the board; a row in an
-- append-only table says who and when. Correcting a total is the second pull request (V022).
--
-- Every rule below is the database's own, so a code path that forgets one is refused at the statement or at commit:
--   1. orders_status_transition: one new move, PENDING_PAYMENT -> CANCELLED, only with no active match; and NEEDS_REVIEW -> PENDING_PAYMENT
--      is refused for a dismissed order.
--   2. orders_assert_status_consistent: lets a CANCELLED order commit (the V016 version expected PENDING_PAYMENT for it).
--   3. orders_assert_cancel_reversed: a CANCELLED order has no live (unreversed) ORDER_ACCEPTED posting (at commit).
--   4. ledger_assert_acceptance_reversal: the reversal of an ORDER_ACCEPTED posting commits only if its order is CANCELLED.
--   5. record_dismissal: exactly one subject, only a record that never reached the books, once per record, append-only, tenant-scoped.

-- ── 1. Order status moves ──────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION orders_status_transition()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID')
       AND NEW.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID') THEN
        RETURN NEW;
    END IF;
    -- The seller states the total of an order that needs review, and it becomes payable (and is posted: V018). Not once it is dismissed.
    IF OLD.status = 'NEEDS_REVIEW' AND NEW.status = 'PENDING_PAYMENT' THEN
        IF EXISTS (SELECT 1 FROM record_dismissal d WHERE d.tenant_id = NEW.tenant_id AND d.order_id = NEW.id) THEN
            RAISE EXCEPTION 'order % was dismissed and cannot become payable', NEW.id USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    -- The seller cancels a payable order that nothing was applied to (task 12b, D8). A partly paid or paid order is unmatched first.
    IF OLD.status = 'PENDING_PAYMENT' AND NEW.status = 'CANCELLED' THEN
        IF EXISTS (SELECT 1 FROM match m WHERE m.tenant_id = NEW.tenant_id AND m.order_id = NEW.id AND m.state IN ('AUTO', 'CONFIRMED')) THEN
            RAISE EXCEPTION 'order % has an active match and cannot be cancelled', NEW.id USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'order status change % to % is not allowed', OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
END;
$$;

-- ── 2. A status change leaves the order consistent with its matches (V016), now with CANCELLED ──
-- A cancelled order is not checked against its matches here: the move is refused with an active match (1), and a match made active
-- afterwards is refused at commit by match_assert_invariants (V016), which finds the order CANCELLED. (A check here was deleted as
-- redundant: no test could tell it from its absence; register 173.)
CREATE OR REPLACE FUNCTION orders_assert_status_consistent()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_applied  BIGINT;
    v_expected TEXT;
BEGIN
    IF NEW.status = 'CANCELLED' THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(amount_minor), 0) INTO v_applied
      FROM match WHERE tenant_id = NEW.tenant_id AND order_id = NEW.id AND state IN ('AUTO', 'CONFIRMED');
    v_expected := CASE WHEN v_applied = 0 THEN 'PENDING_PAYMENT'
                       WHEN v_applied < NEW.total_minor THEN 'PARTIALLY_PAID'
                       ELSE 'PAID' END;
    IF NEW.status <> v_expected THEN
        RAISE EXCEPTION 'order %: the status is % but its matches make it %', NEW.id, NEW.status, v_expected
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- ── 3. At commit: a cancelled order carries nothing in the books ─────────────
CREATE OR REPLACE FUNCTION orders_assert_cancel_reversed()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM ledger_txn t
                WHERE t.tenant_id = NEW.tenant_id AND t.kind = 'ORDER_ACCEPTED' AND t.ref_id = NEW.id
                  AND NOT EXISTS (SELECT 1 FROM ledger_txn r WHERE r.tenant_id = t.tenant_id AND r.reverses_txn_id = t.id)) THEN
        RAISE EXCEPTION 'order % was cancelled without the reversal of its ORDER_ACCEPTED posting', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_orders_assert_cancel_reversed
    AFTER UPDATE OF status ON orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'CANCELLED' AND OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION orders_assert_cancel_reversed();

-- ── 4. At commit: an order's acceptance is reversed only when the order is cancelled ──
-- Without this, reversing ORDER_ACCEPTED on an open order would leave a payable order that the books no longer know, and RECEIVABLE
-- and SALES would still agree (both would drop by its total). V022 adds the second allowed case: a corrected total.
CREATE OR REPLACE FUNCTION ledger_assert_acceptance_reversal()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_kind   TEXT;
    v_order  UUID;
    v_status TEXT;
BEGIN
    SELECT kind, ref_id INTO v_kind, v_order FROM ledger_txn WHERE id = NEW.reverses_txn_id AND tenant_id = NEW.tenant_id;
    IF v_kind IS DISTINCT FROM 'ORDER_ACCEPTED' THEN
        RETURN NULL;
    END IF;
    SELECT status INTO v_status FROM orders WHERE id = v_order AND tenant_id = NEW.tenant_id;
    IF v_status IS DISTINCT FROM 'CANCELLED' THEN
        RAISE EXCEPTION 'the ORDER_ACCEPTED posting of order % was reversed but the order is %', v_order, COALESCE(v_status, 'missing')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_ledger_assert_acceptance_reversal
    AFTER INSERT ON ledger_txn
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.reverses_txn_id IS NOT NULL)
    EXECUTE FUNCTION ledger_assert_acceptance_reversal();

-- ── 5. record_dismissal: append-only, one row per dismissed record ───────────
CREATE TABLE record_dismissal (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id),
    order_id     UUID,
    payment_id   UUID,
    dismissed_by UUID        NOT NULL,
    dismissed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_record_dismissal_one_subject CHECK (num_nonnulls(order_id, payment_id) = 1),
    CONSTRAINT fk_record_dismissal_order_same_tenant FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id),
    CONSTRAINT fk_record_dismissal_payment_same_tenant FOREIGN KEY (payment_id, tenant_id) REFERENCES payment (id, tenant_id),
    CONSTRAINT fk_record_dismissal_actor_same_tenant FOREIGN KEY (dismissed_by, tenant_id) REFERENCES app_user (id, tenant_id)
);
CREATE UNIQUE INDEX uq_record_dismissal_order   ON record_dismissal (tenant_id, order_id)   WHERE order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_record_dismissal_payment ON record_dismissal (tenant_id, payment_id) WHERE payment_id IS NOT NULL;

-- Only a record that never reached the books: an UNPARSED order or payment, or an order whose total is not known yet (NEEDS_REVIEW).
-- None of them has a posting or a match, so nothing is reversed. (Locks the subject row, so a dismissal and an approval of the same
-- NEEDS_REVIEW order are decided one after the other.)
CREATE OR REPLACE FUNCTION record_dismissal_allowed()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status TEXT;
BEGIN
    IF NEW.order_id IS NOT NULL THEN
        SELECT status INTO v_status FROM orders WHERE id = NEW.order_id AND tenant_id = NEW.tenant_id FOR NO KEY UPDATE;
        IF v_status IS NULL OR v_status NOT IN ('UNPARSED', 'NEEDS_REVIEW') THEN
            RAISE EXCEPTION 'order % is % and cannot be dismissed', NEW.order_id, COALESCE(v_status, 'missing') USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        SELECT status INTO v_status FROM payment WHERE id = NEW.payment_id AND tenant_id = NEW.tenant_id FOR NO KEY UPDATE;
        IF v_status IS DISTINCT FROM 'UNPARSED' THEN
            RAISE EXCEPTION 'payment % is % and cannot be dismissed', NEW.payment_id, COALESCE(v_status, 'missing') USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_record_dismissal_allowed
    BEFORE INSERT ON record_dismissal
    FOR EACH ROW EXECUTE FUNCTION record_dismissal_allowed();

CREATE TRIGGER trg_record_dismissal_no_update_delete BEFORE UPDATE OR DELETE ON record_dismissal
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();
CREATE TRIGGER trg_record_dismissal_no_truncate BEFORE TRUNCATE ON record_dismissal
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_immutable();

ALTER TABLE record_dismissal ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON record_dismissal
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

REVOKE ALL ON record_dismissal FROM PUBLIC;
GRANT SELECT, INSERT ON record_dismissal TO chatledger_app;
