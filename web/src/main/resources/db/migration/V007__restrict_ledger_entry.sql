-- Migration V007: revoke UPDATE and DELETE on ledger_entry from the application role.
-- The app connects as the role defined by $DB_USER (the "app role").
-- After this migration that role can INSERT but never modify posted entries.
-- Requirement: 4.2 (ledger entries are append-only).

-- Revoke write permissions from the application role.
-- ${flyway:defaultSchema} resolves to the schema in use (public by default).
REVOKE UPDATE, DELETE ON ledger_entry FROM PUBLIC;

-- If a dedicated app role is used, also revoke from it explicitly.
-- The DO block silently skips the revoke if the role does not exist yet,
-- so this migration is safe to run before the role is created.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user) THEN
        EXECUTE 'REVOKE UPDATE, DELETE ON ledger_entry FROM ' || current_user;
    END IF;
END $$;
