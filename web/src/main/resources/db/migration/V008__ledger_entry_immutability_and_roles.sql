-- Migration V008: ledger immutability triggers + least-privilege grants.
--
-- Closes the gap left by V007 (see docs/kiro-notes.md, task 2.3).
--
-- Preconditions (created OUTSIDE migrations by docker/initdb/01_create_roles.sh,
-- so that no credential ever appears in a migration):
--   chatledger_migrate  non-superuser schema owner; the only role Flyway uses.
--   chatledger_app      NOLOGIN group role holding the application privileges.
--   <APP_LOGIN_USER>    login role, member of chatledger_app, INHERIT.
--
-- Two independent layers:
--
--   Layer 1 - triggers on ledger_entry and ledger_txn that always raise an
--             exception. They block UPDATE, DELETE and TRUNCATE for every role
--             that cannot alter the triggers themselves.
--             LIMIT: the table owner (chatledger_migrate) or a superuser can
--             run ALTER TABLE ... DISABLE TRIGGER or DROP TRIGGER and then
--             mutate rows. Compensation: the application never connects as the
--             owner or as a superuser, and Flyway is the only user of the
--             owner role.
--
--   Layer 2 - chatledger_app has only SELECT/INSERT on the ledger tables, is not
--             the owner and is not a superuser, so it can neither mutate rows
--             nor disable/drop the triggers.
--
-- Requirement: 4.2 (append-only ledger; corrections are reversal entries).

-- 1. Trigger function (plain trigger function, no SECURITY DEFINER).
CREATE OR REPLACE FUNCTION ledger_immutable()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        '% is append-only. Mutations (%, row %) are forbidden. '
        'Post a reversal transaction instead.',
        TG_TABLE_NAME,
        TG_OP,
        CASE TG_OP
            WHEN 'DELETE' THEN OLD.id::text
            WHEN 'UPDATE' THEN NEW.id::text
            ELSE '(statement-level)'
        END;
END;
$$;

-- 2. ledger_entry
DROP TRIGGER IF EXISTS trg_ledger_entry_no_update_delete ON ledger_entry;
CREATE TRIGGER trg_ledger_entry_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();

DROP TRIGGER IF EXISTS trg_ledger_entry_no_truncate ON ledger_entry;
CREATE TRIGGER trg_ledger_entry_no_truncate
    BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_immutable();

-- 3. ledger_txn (a posted transaction header is as immutable as its entries)
DROP TRIGGER IF EXISTS trg_ledger_txn_no_update_delete ON ledger_txn;
CREATE TRIGGER trg_ledger_txn_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_txn
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();

DROP TRIGGER IF EXISTS trg_ledger_txn_no_truncate ON ledger_txn;
CREATE TRIGGER trg_ledger_txn_no_truncate
    BEFORE TRUNCATE ON ledger_txn
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_immutable();

-- 4. Schema access and grants for the application group role.
GRANT USAGE ON SCHEMA public TO chatledger_app;

REVOKE ALL ON ledger_entry FROM PUBLIC;
REVOKE ALL ON ledger_txn   FROM PUBLIC;
REVOKE ALL ON account      FROM PUBLIC;

GRANT SELECT, INSERT ON ledger_entry TO chatledger_app;
GRANT SELECT, INSERT ON ledger_txn   TO chatledger_app;
GRANT SELECT, INSERT ON account      TO chatledger_app;

-- Other tables are read-only for now; write access is granted per task.
GRANT SELECT ON tenant, app_user, customer, orders, order_line,
                payment, match, flag, audit_log
    TO chatledger_app;

-- 5. Sequences. The current schema has none (all keys are UUIDs), so this
-- grant is a no-op today.
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO chatledger_app;

-- 6. No default privileges, on purpose. Every new table, and every new
-- sequence, must be granted to chatledger_app explicitly in the migration that
-- creates it. A blanket default would hand the app role access to tables nobody
-- reviewed (for example a future table holding secrets), which the
-- least-privilege rule in the security steering rules out. A sequence default
-- is not justified either: there are no sequences, and a sequence belongs to a
-- table whose grant is already explicit, so its grant belongs next to that one.
