-- Migration V009: reversals, balance invariant and tenant consistency, enforced
-- by the database so that no code path can commit a ledger that breaks them.
--
-- V001-V008 are never edited. Nothing here updates or deletes a row, so all of
-- it works with the immutability triggers from V008.
--
-- Requirement: 4.1 (balanced entries), 4.2 (corrections are reversal entries),
-- 4.3 (ledger data is separate per tenant). Task: 2.1.

-- ── 1. Reversal link ────────────────────────────────────────────────────────
--
-- reverses_txn_id points from a reversal to the transaction it undoes.
--
-- A CHECK constraint cannot look at another row, so "the reversal has the same
-- tenant" is a composite foreign key: (reverses_txn_id, tenant_id) must match
-- (id, tenant_id) of an existing transaction. For an ordinary transaction
-- reverses_txn_id is NULL and the foreign key is not checked (MATCH SIMPLE).
--
-- The unique constraint on reverses_txn_id means a transaction can be reversed
-- at most once. NULLs do not collide, so any number of ordinary transactions is
-- fine.
ALTER TABLE ledger_txn ADD COLUMN reverses_txn_id UUID;

ALTER TABLE ledger_txn
    ADD CONSTRAINT uq_ledger_txn_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE ledger_txn
    ADD CONSTRAINT fk_ledger_txn_reverses_same_tenant
        FOREIGN KEY (reverses_txn_id, tenant_id)
        REFERENCES ledger_txn (id, tenant_id);

ALTER TABLE ledger_txn
    ADD CONSTRAINT uq_ledger_txn_reverses UNIQUE (reverses_txn_id);

ALTER TABLE ledger_txn
    ADD CONSTRAINT chk_ledger_txn_not_self_reversal
        CHECK (reverses_txn_id IS NULL OR reverses_txn_id <> id);

-- ── 2. An entry's account must belong to the entry's transaction's tenant ───
--
-- ledger_entry has no tenant column of its own, and adding one would need an
-- UPDATE to back-fill, which V008 forbids. A BEFORE INSERT trigger compares the
-- two tenants instead. If either parent row is missing it steps aside and the
-- foreign key reports the real problem.
CREATE OR REPLACE FUNCTION ledger_assert_entry_tenant()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_txn_tenant  UUID;
    v_acct_tenant UUID;
BEGIN
    SELECT tenant_id INTO v_txn_tenant  FROM ledger_txn WHERE id = NEW.txn_id;
    SELECT tenant_id INTO v_acct_tenant FROM account    WHERE id = NEW.account_id;

    IF v_txn_tenant IS NOT NULL AND v_acct_tenant IS NOT NULL
       AND v_txn_tenant <> v_acct_tenant THEN
        RAISE EXCEPTION
            'ledger entry %: account % belongs to another tenant than transaction %',
            NEW.id, NEW.account_id, NEW.txn_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_entry_same_tenant
    BEFORE INSERT ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_entry_tenant();

-- That check is only worth something while an account cannot change tenant.
CREATE OR REPLACE FUNCTION account_tenant_immutable()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'account % cannot move to another tenant', OLD.id
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_account_tenant_immutable
    BEFORE UPDATE OF tenant_id ON account
    FOR EACH ROW
    WHEN (NEW.tenant_id IS DISTINCT FROM OLD.tenant_id)
    EXECUTE FUNCTION account_tenant_immutable();

-- ── 3. Debits equal credits, checked at commit ──────────────────────────────
--
-- A deferred constraint trigger runs when the transaction commits, after all
-- the entries of a ledger transaction are in. It fires for every inserted entry
-- and for every inserted transaction header, so a header with no entries (which
-- no entry trigger would ever see) is caught as well.
--
-- Sums are NUMERIC, so the check itself can never overflow.
CREATE OR REPLACE FUNCTION ledger_assert_txn_balanced()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
DECLARE
    v_txn     UUID;
    v_debits  NUMERIC;
    v_credits NUMERIC;
    v_count   BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'ledger_txn' THEN
        v_txn := NEW.id;
    ELSE
        v_txn := NEW.txn_id;
    END IF;

    SELECT COALESCE(SUM(debit_minor), 0), COALESCE(SUM(credit_minor), 0), COUNT(*)
      INTO v_debits, v_credits, v_count
      FROM ledger_entry
     WHERE txn_id = v_txn;

    IF v_count < 2 THEN
        RAISE EXCEPTION
            'ledger transaction % must have at least two entries, found %', v_txn, v_count
            USING ERRCODE = 'check_violation';
    END IF;

    IF v_debits <> v_credits THEN
        RAISE EXCEPTION
            'ledger transaction % is not balanced: debits % credits %', v_txn, v_debits, v_credits
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entry_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_txn_balanced();

CREATE CONSTRAINT TRIGGER trg_ledger_txn_balanced
    AFTER INSERT ON ledger_txn
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_txn_balanced();

-- ── 4. Privileges for the application role ──────────────────────────────────
--
-- Stated again here, on purpose, so this migration shows everything the ledger
-- needs: append-only writes and reads, nothing else. There is no UPDATE and no
-- DELETE on any ledger table, and no default privileges. The new column is
-- covered by the table-level grants. The constraint triggers read ledger_entry,
-- ledger_txn and account, which the role can already SELECT.
GRANT SELECT, INSERT ON ledger_txn   TO chatledger_app;
GRANT SELECT, INSERT ON ledger_entry TO chatledger_app;
GRANT SELECT, INSERT ON account      TO chatledger_app;
