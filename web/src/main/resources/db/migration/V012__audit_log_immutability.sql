-- Migration V012: audit_log is append-only, like the ledger.
--
-- An audit trail that can be edited or emptied is not an audit trail. The same
-- trigger function that protects ledger_entry and ledger_txn (V008) now guards
-- audit_log: UPDATE and DELETE are refused per row, TRUNCATE per statement.
--
-- The application role already has only SELECT and INSERT on audit_log (V011).
-- The triggers are the second layer. As with the ledger, the limit is that the
-- table owner or a superuser can disable or drop them; the compensation is that
-- the application never connects as the owner or a superuser, and Flyway is the
-- only user of the owner role.
--
-- Requirement: 9 (audit trail), 4.2 by analogy. Task 3.1.

DROP TRIGGER IF EXISTS trg_audit_log_no_update_delete ON audit_log;
CREATE TRIGGER trg_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();

DROP TRIGGER IF EXISTS trg_audit_log_no_truncate ON audit_log;
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_immutable();

-- Privileges are unchanged: the application role keeps SELECT and INSERT only
-- (V011). Restated so this migration shows the end state. No default privileges.
GRANT SELECT, INSERT ON audit_log TO chatledger_app;
