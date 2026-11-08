-- Migration V016: matching (task 6).
--
--   1. match gets what matching needs: excess_minor (the part of a payment above the order balance, requirement 3.6),
--      ledger_txn_id (the posting this match made), created_at. amount_minor becomes nullable, but only for SUGGESTED and
--      REJECTED rows (a suggestion applies nothing yet); CHECKs tie every column to the state.
--   2. Invariants that make a wrong match impossible even if application code is wrong:
--        - one payment has at most one active (AUTO or CONFIRMED) match;
--        - one pair of order and payment has at most one open (SUGGESTED, AUTO or CONFIRMED) match;
--        - one ledger posting per business event (ORDER_ACCEPTED per order, PAYMENT_MATCHED per match);
--        - at commit, the active matches of an order add up to at most its total, an active match uses its payment whole
--          (amount + excess = payment amount), the order status is the function of its active matches, a payment is MATCHED
--          exactly when it has an active match, an AUTO match needs a provider transaction id, and every active match has a
--          ledger posting with exactly its amounts on the right accounts (a reversed match has its reversal).
--   3. The application role may now change a status, and only a status: UPDATE (status) on orders and payment, INSERT on
--      match and UPDATE of six named match columns. Never DELETE, never any other column. BEFORE UPDATE triggers allow only the
--      moves the matcher makes, so even a bug cannot mark a DUPLICATE payment matched or an UNPARSED order paid.
--
-- Account codes used by the checks (ChartOfAccounts in the application must agree; a test asserts it):
--   1000 Mobile money clearing, 1100 Accounts receivable, 2100 Customer credit, 4000 Sales revenue.
--
-- Requirement: 3, 4. Task 6.
-- ── 1. Columns ─────────────────────────────────────────────────────────────
ALTER TABLE match ADD COLUMN excess_minor  BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE match ADD COLUMN ledger_txn_id UUID;
ALTER TABLE match ADD COLUMN created_at    TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE match ALTER COLUMN amount_minor DROP NOT NULL;
ALTER TABLE match ADD CONSTRAINT fk_match_ledger_txn_same_tenant
    FOREIGN KEY (ledger_txn_id, tenant_id) REFERENCES ledger_txn (id, tenant_id);

-- ── 2a. Checks (NOT VALID: the table may hold rows from before; they apply to every new and changed row) ──
-- IS NOT NULL is written out on purpose: a CHECK that evaluates to unknown passes.
ALTER TABLE match ADD CONSTRAINT chk_match_by_state CHECK (
       (state IN ('SUGGESTED', 'REJECTED')
            AND amount_minor IS NULL AND excess_minor = 0 AND ledger_txn_id IS NULL)
    OR (state IN ('AUTO', 'CONFIRMED', 'REVERSED')
            AND amount_minor IS NOT NULL AND amount_minor BETWEEN 1 AND 1000000000000
            AND excess_minor BETWEEN 0 AND 1000000000000 AND ledger_txn_id IS NOT NULL)
) NOT VALID;
ALTER TABLE match ADD CONSTRAINT chk_match_decided CHECK (
       (state = 'SUGGESTED' AND decided_by IS NULL AND decided_at IS NULL)
    OR (state = 'AUTO'      AND decided_by IS NULL AND decided_at IS NULL)
    OR (state = 'CONFIRMED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    OR (state = 'REJECTED'  AND decided_at IS NOT NULL)
    OR (state = 'REVERSED'  AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
) NOT VALID;
ALTER TABLE match ADD CONSTRAINT chk_match_score CHECK (score BETWEEN 0 AND 100) NOT VALID;
-- Reasons are codes and integers: letters, digits, underscore, colon, comma, quote, braces, brackets and space. No free text fits.
ALTER TABLE match ADD CONSTRAINT chk_match_reasons
    CHECK (char_length(reasons_json) <= 2000 AND reasons_json ~ '^[][A-Za-z0-9_:,"{} ]*$') NOT VALID;

-- ── 2b. Unique indexes ─────────────────────────────────────────────────────
CREATE UNIQUE INDEX uq_match_active_payment ON match (payment_id) WHERE state IN ('AUTO', 'CONFIRMED');
CREATE UNIQUE INDEX uq_match_open_pair ON match (order_id, payment_id) WHERE state IN ('SUGGESTED', 'AUTO', 'CONFIRMED');
CREATE UNIQUE INDEX uq_ledger_txn_posting ON ledger_txn (tenant_id, kind, ref_id)
    WHERE reverses_txn_id IS NULL AND kind IN ('ORDER_ACCEPTED', 'PAYMENT_MATCHED');
-- Lookups the matcher makes: candidates by status, and orders by reference key (the reference proves nothing if it is shared).
CREATE INDEX idx_orders_tenant_status  ON orders (tenant_id, status, created_at DESC);
CREATE INDEX idx_payment_tenant_status ON payment (tenant_id, status, created_at DESC);
CREATE INDEX idx_orders_ref_upper      ON orders (tenant_id, upper(order_ref)) WHERE order_ref IS NOT NULL;

-- ── 3a. Status transitions: only the moves the matcher makes ────────────────
-- Orders: between PENDING_PAYMENT, PARTIALLY_PAID and PAID, in any direction (a payment, an unmatch). Nothing into or out of
-- NEEDS_REVIEW, UNPARSED or CANCELLED: those moves belong to later tasks, which add their own named transition.
CREATE OR REPLACE FUNCTION orders_status_transition()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID')
       AND NEW.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'order status change % to % is not allowed', OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
END;
$$;
CREATE TRIGGER trg_orders_status_transition
    BEFORE UPDATE OF status ON orders
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION orders_status_transition();

-- Payments: free to matched and back (a RECEIVED payment goes back to RECEIVED, an UNVERIFIED one to UNVERIFIED; the existing
-- chk_payment_status_links ties each to its transaction id), and RECEIVED to UNMATCHED and back (a seller's decision).
-- DUPLICATE and UNPARSED never change.
CREATE OR REPLACE FUNCTION payment_status_transition()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (OLD.status, NEW.status) IN (
            ('RECEIVED', 'MATCHED'), ('UNVERIFIED', 'MATCHED'), ('MATCHED', 'RECEIVED'), ('MATCHED', 'UNVERIFIED'),
            ('RECEIVED', 'UNMATCHED'), ('UNMATCHED', 'RECEIVED'), ('UNMATCHED', 'MATCHED')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'payment status change % to % is not allowed', OLD.status, NEW.status
        USING ERRCODE = 'check_violation';
END;
$$;
CREATE TRIGGER trg_payment_status_transition
    BEFORE UPDATE OF status ON payment
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION payment_status_transition();

-- ── 3b. match: only forward moves; the amounts are set once, at confirmation ──
CREATE OR REPLACE FUNCTION match_update_rules()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.state = OLD.state THEN
        IF NEW.amount_minor IS DISTINCT FROM OLD.amount_minor OR NEW.excess_minor <> OLD.excess_minor
           OR NEW.ledger_txn_id IS DISTINCT FROM OLD.ledger_txn_id OR NEW.decided_by IS DISTINCT FROM OLD.decided_by
           OR NEW.decided_at IS DISTINCT FROM OLD.decided_at THEN
            RAISE EXCEPTION 'match % cannot change without a change of state', OLD.id
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF (OLD.state, NEW.state) NOT IN (
            ('SUGGESTED', 'CONFIRMED'), ('SUGGESTED', 'REJECTED'), ('AUTO', 'REVERSED'), ('CONFIRMED', 'REVERSED')) THEN
        RAISE EXCEPTION 'match state change % to % is not allowed', OLD.state, NEW.state
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT (OLD.state = 'SUGGESTED' AND NEW.state = 'CONFIRMED')
       AND (NEW.amount_minor IS DISTINCT FROM OLD.amount_minor OR NEW.excess_minor <> OLD.excess_minor
            OR NEW.ledger_txn_id IS DISTINCT FROM OLD.ledger_txn_id) THEN
        RAISE EXCEPTION 'the amounts of match % are set once, when it is confirmed', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_match_update_rules
    BEFORE UPDATE ON match
    FOR EACH ROW EXECUTE FUNCTION match_update_rules();

-- ── 2c. Consistency at commit (deferred, like the ledger balance check) ─────
-- Fires for a match that is, or was, active. Locks the order row, so that two transactions adding matches to one order check their sums
-- one after the other (the second sees the first's committed row) and cannot both pass. The application has already locked the
-- payment and then the order, in that order, so this adds no new lock order and cannot deadlock with it.
CREATE OR REPLACE FUNCTION match_assert_invariants()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_pay_amount BIGINT;
    v_pay_status TEXT;
    v_pay_tx     TEXT;
    v_pay_cur    TEXT;
    v_ord_total  BIGINT;
    v_ord_status TEXT;
    v_ord_cur    TEXT;
    v_applied    BIGINT;
    v_expected   TEXT;
    v_active_pay BIGINT;
    v_debit      BIGINT;
    v_credit_ar  BIGINT;
    v_credit_cc  BIGINT;
BEGIN
    IF NEW.state NOT IN ('AUTO', 'CONFIRMED', 'REVERSED') THEN
        RETURN NULL;
    END IF;

    SELECT amount_minor, status, tx_id, currency INTO v_pay_amount, v_pay_status, v_pay_tx, v_pay_cur
      FROM payment WHERE id = NEW.payment_id AND tenant_id = NEW.tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'match %: payment not found for this tenant', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    SELECT total_minor, status, currency INTO v_ord_total, v_ord_status, v_ord_cur
      FROM orders WHERE id = NEW.order_id AND tenant_id = NEW.tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'match %: order not found for this tenant', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    IF v_ord_total IS NULL THEN
        RAISE EXCEPTION 'match %: the order has no total', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    IF v_pay_cur <> v_ord_cur THEN
        RAISE EXCEPTION 'match %: payment and order are in different currencies', NEW.id USING ERRCODE = 'check_violation';
    END IF;

    -- the order: never more than its total, and its status is the function of its active matches
    SELECT COALESCE(SUM(amount_minor), 0) INTO v_applied
      FROM match WHERE tenant_id = NEW.tenant_id AND order_id = NEW.order_id AND state IN ('AUTO', 'CONFIRMED');
    IF v_applied > v_ord_total THEN
        RAISE EXCEPTION 'match %: the active matches of the order exceed its total', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    v_expected := CASE WHEN v_applied = 0 THEN 'PENDING_PAYMENT'
                       WHEN v_applied < v_ord_total THEN 'PARTIALLY_PAID'
                       ELSE 'PAID' END;
    IF v_ord_status <> v_expected THEN
        RAISE EXCEPTION 'match %: the order status is % but its matches make it %', NEW.id, v_ord_status, v_expected
            USING ERRCODE = 'check_violation';
    END IF;

    -- the payment: MATCHED exactly when it has an active match
    SELECT COUNT(*) INTO v_active_pay
      FROM match WHERE tenant_id = NEW.tenant_id AND payment_id = NEW.payment_id AND state IN ('AUTO', 'CONFIRMED');
    IF (v_active_pay > 0) <> (v_pay_status = 'MATCHED') THEN
        RAISE EXCEPTION 'match %: the payment status is % but it has % active matches', NEW.id, v_pay_status, v_active_pay
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.state = 'REVERSED' THEN
        IF NOT EXISTS (SELECT 1 FROM ledger_txn r WHERE r.tenant_id = NEW.tenant_id AND r.reverses_txn_id = NEW.ledger_txn_id) THEN
            RAISE EXCEPTION 'match %: a reversed match needs the reversal of its posting', NEW.id USING ERRCODE = 'check_violation';
        END IF;
        RETURN NULL;
    END IF;

    -- an active match uses its payment whole
    IF NEW.amount_minor + NEW.excess_minor <> v_pay_amount THEN
        RAISE EXCEPTION 'match %: amount plus excess is not the payment amount', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.state = 'AUTO' AND v_pay_tx IS NULL THEN
        RAISE EXCEPTION 'match %: an automatic match needs a provider transaction id', NEW.id USING ERRCODE = 'check_violation';
    END IF;

    -- and has its posting, with exactly its amounts on the right accounts
    SELECT COALESCE(SUM(CASE WHEN a.code = '1000' THEN e.debit_minor ELSE 0 END), 0),
           COALESCE(SUM(CASE WHEN a.code = '1100' THEN e.credit_minor ELSE 0 END), 0),
           COALESCE(SUM(CASE WHEN a.code = '2100' THEN e.credit_minor ELSE 0 END), 0)
      INTO v_debit, v_credit_ar, v_credit_cc
      FROM ledger_txn t
      JOIN ledger_entry e ON e.txn_id = t.id
      JOIN account a ON a.id = e.account_id AND a.tenant_id = t.tenant_id
     WHERE t.id = NEW.ledger_txn_id AND t.tenant_id = NEW.tenant_id AND t.kind = 'PAYMENT_MATCHED'
       AND t.ref_id = NEW.id AND t.reverses_txn_id IS NULL;
    IF v_debit <> NEW.amount_minor + NEW.excess_minor OR v_credit_ar <> NEW.amount_minor OR v_credit_cc <> NEW.excess_minor THEN
        RAISE EXCEPTION 'match %: its ledger posting does not have its amounts', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_match_invariants
    AFTER INSERT OR UPDATE ON match
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION match_assert_invariants();

-- A status change made on its own must also leave the books consistent.
CREATE OR REPLACE FUNCTION orders_assert_status_consistent()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_applied  BIGINT;
    v_expected TEXT;
BEGIN
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
CREATE CONSTRAINT TRIGGER trg_orders_status_consistent
    AFTER UPDATE OF status ON orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION orders_assert_status_consistent();

CREATE OR REPLACE FUNCTION payment_assert_status_consistent()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_active BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_active
      FROM match WHERE tenant_id = NEW.tenant_id AND payment_id = NEW.id AND state IN ('AUTO', 'CONFIRMED');
    IF (v_active > 0) <> (NEW.status = 'MATCHED') THEN
        RAISE EXCEPTION 'payment %: the status is % but it has % active matches', NEW.id, NEW.status, v_active
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_payment_status_consistent
    AFTER UPDATE OF status ON payment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION payment_assert_status_consistent();

-- ── 3c. Privileges for the application role ────────────────────────────────
-- Explicit grants only (no default privileges). Column lists, never the whole table: the application can change a status and the
-- named columns of a match, and nothing else; there is no DELETE and no TRUNCATE. The three amount columns are in the match grant
-- only because a suggestion is created without amounts and receives them when the seller confirms; match_update_rules lets them
-- change in that one transition and never again.
GRANT UPDATE (status) ON orders  TO chatledger_app;
GRANT UPDATE (status) ON payment TO chatledger_app;
GRANT INSERT ON match TO chatledger_app;
GRANT UPDATE (state, decided_by, decided_at, amount_minor, excess_minor, ledger_txn_id) ON match TO chatledger_app;
