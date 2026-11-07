-- Migration V011: row-level security on every tenant table.
--
-- Second layer under application scoping (design.md, "Tenancy and authentication",
-- decision 2). The application sets app.tenant_id for the transaction with
-- set_config('app.tenant_id', <uuid>, true). Policies compare against it.
-- When it is unset every comparison is not-true, so reads return nothing and
-- writes are refused: the policies fail closed.
--
-- ENABLE, not FORCE: the table owner (chatledger_migrate, used by Flyway only)
-- bypasses the policies. The application role is not the owner, not a superuser
-- and not BYPASSRLS, so it is always subject to them. TRUNCATE and REFERENCES are
-- not covered by row-level security; the application role holds neither.
--
-- Limit, named: the setting is an ordinary session variable, so SQL run by the
-- application role could set it. This layer protects against our own bugs, not
-- against SQL injection. See the trade-off register in design.md.
--
-- Requirement: 9.1, 4.3. Task 3.2.

-- ── 1. The current tenant ───────────────────────────────────────────────────
--
-- Unset or empty gives NULL. A value that is not a UUID raises an error, which
-- also refuses the statement.
CREATE OR REPLACE FUNCTION app_current_tenant()
    RETURNS UUID
    LANGUAGE sql
    STABLE
AS $$
    SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid
$$;

REVOKE ALL ON FUNCTION app_current_tenant() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_current_tenant() TO chatledger_app;

-- ── 2. V009's tenant trigger must fail closed ───────────────────────────────
--
-- The V009 version returned quietly when it could not see the account or the
-- transaction, expecting the foreign key to report it. Under row-level security
-- another tenant's account is invisible to the trigger, and foreign-key checks
-- bypass row-level security, so such an entry would have committed. Anything the
-- trigger cannot see in this tenant is now a violation.
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

    IF v_txn_tenant IS NULL OR v_acct_tenant IS NULL OR v_txn_tenant <> v_acct_tenant THEN
        RAISE EXCEPTION
            'ledger entry %: account % belongs to another tenant than transaction %, or is not visible to this tenant',
            NEW.id, NEW.account_id, NEW.txn_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- ── 3. Policies ─────────────────────────────────────────────────────────────
--
-- One policy per table, for the application role, covering reads and writes:
-- a row is visible and writable only when it belongs to the current tenant.

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    FOR SELECT TO chatledger_app
    USING (id = app_current_tenant());

ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app_user
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON orders
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE order_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_line
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE match ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON match
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE flag ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON flag
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON account
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE ledger_txn ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_txn
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- ledger_entry has no tenant column and is immutable, so it cannot be back-filled.
-- It follows its transaction: row-level security on ledger_txn applies inside
-- this sub-select, so only the current tenant's transactions are visible to it.
ALTER TABLE ledger_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_entry
    FOR ALL TO chatledger_app
    USING (EXISTS (SELECT 1 FROM ledger_txn t WHERE t.id = ledger_entry.txn_id))
    WITH CHECK (EXISTS (SELECT 1 FROM ledger_txn t WHERE t.id = ledger_entry.txn_id));

-- ── 4. Privileges for the application role ──────────────────────────────────
--
-- audit_log is append-only for the application: insert and read, never update
-- or delete (not granted). Everything else keeps its V008 grants. No default
-- privileges; the role owns none of these tables.
GRANT SELECT, INSERT ON audit_log TO chatledger_app;
