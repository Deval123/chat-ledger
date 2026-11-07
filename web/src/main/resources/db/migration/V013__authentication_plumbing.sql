-- Migration V013: authentication plumbing.
--
--   1. password_hash must look like an Argon2id hash, so a plaintext password can
--      never be stored in app_user, whichever code path writes it.
--   2. resolve_tenant_for_login: the one step of login that runs before any tenant
--      is known. It returns only the tenant id for a workspace code.
--   3. provision_tenant and provision_user: how an operator creates a workspace
--      and its users. They are the only way users and tenants are created.
--
-- The three functions are SECURITY DEFINER (they must see rows the caller cannot).
-- Each is small, has a fixed search_path, qualifies every name, returns the least it
-- can, and is executable only by the role named below. The isolation guard fails the
-- build if any other SECURITY DEFINER function appears.
--
-- Roles are created outside migrations (docker/initdb/01_create_roles.sh) because no
-- migration may hold a credential. The grants to chatledger_provisioner are made only
-- if that role exists, so this migration also applies to a database created before the
-- role was introduced; see the README for the one-line grant to run in that case.
--
-- Requirement: 9.1, 9.4. Tasks 3.4, 3.5, 3.6.

-- ── 1. Stored password hashes are Argon2id ─────────────────────────────────
--
-- NOT VALID: new and changed rows are checked; rows that existed before are not
-- re-checked (there can be none created by the application, which could not create
-- users before this task).
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_password_hash_format
    CHECK (password_hash ~ '^\{argon2id\}\$argon2id\$v=19\$m=[0-9]+,t=[0-9]+,p=[0-9]+\$[A-Za-z0-9+/]+\$[A-Za-z0-9+/]+$')
    NOT VALID;

-- ── 2. Login: workspace code to tenant id ──────────────────────────────────
--
-- Runs as the owner so it can read tenant before a tenant is set. Returns NULL for
-- an unknown or disabled workspace and nothing else, so it cannot be used to list
-- tenants or read their data.
CREATE OR REPLACE FUNCTION resolve_tenant_for_login(p_slug TEXT)
    RETURNS UUID
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id
      FROM public.tenant t
     WHERE t.slug = pg_catalog.lower(pg_catalog.btrim(p_slug))
       AND t.disabled_at IS NULL
$$;

REVOKE ALL ON FUNCTION resolve_tenant_for_login(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_tenant_for_login(TEXT) TO chatledger_app;

-- ── 3. Provisioning ────────────────────────────────────────────────────────

-- A workspace and its first SELLER, in one transaction. Format rules (workspace code,
-- email, hash) are enforced by the table constraints, so a bad value fails the same way
-- whoever calls this.
CREATE OR REPLACE FUNCTION provision_tenant(p_slug TEXT, p_name TEXT, p_email TEXT, p_password_hash TEXT)
    RETURNS TABLE (out_tenant_id UUID, out_user_id UUID)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_tenant UUID := pg_catalog.gen_random_uuid();
    v_user   UUID := pg_catalog.gen_random_uuid();
BEGIN
    IF p_name IS NULL OR pg_catalog.char_length(pg_catalog.btrim(p_name)) NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION 'invalid workspace name' USING ERRCODE = 'check_violation';
    END IF;

    INSERT INTO public.tenant (id, name, currency, scale, slug)
    VALUES (v_tenant, pg_catalog.btrim(p_name), 'XAF', 0, p_slug);

    INSERT INTO public.app_user (id, tenant_id, email, password_hash, role)
    VALUES (v_user, v_tenant, p_email, p_password_hash, 'SELLER');

    RETURN QUERY SELECT v_tenant, v_user;
END;
$$;

-- A user in an existing, enabled workspace.
CREATE OR REPLACE FUNCTION provision_user(p_slug TEXT, p_email TEXT, p_role TEXT, p_password_hash TEXT)
    RETURNS UUID
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_tenant UUID;
    v_user   UUID := pg_catalog.gen_random_uuid();
BEGIN
    IF p_role IS NULL OR p_role NOT IN ('SELLER', 'HELPER') THEN
        RAISE EXCEPTION 'invalid role' USING ERRCODE = 'check_violation';
    END IF;

    SELECT t.id INTO v_tenant
      FROM public.tenant t
     WHERE t.slug = p_slug AND t.disabled_at IS NULL;
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'workspace not found' USING ERRCODE = 'no_data_found';
    END IF;

    INSERT INTO public.app_user (id, tenant_id, email, password_hash, role)
    VALUES (v_user, v_tenant, p_email, p_password_hash, p_role);

    RETURN v_user;
END;
$$;

REVOKE ALL ON FUNCTION provision_tenant(TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION provision_user(TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;

-- Only the provisioner role may call them, and the application role may not.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatledger_provisioner') THEN
        GRANT EXECUTE ON FUNCTION provision_tenant(TEXT, TEXT, TEXT, TEXT) TO chatledger_provisioner;
        GRANT EXECUTE ON FUNCTION provision_user(TEXT, TEXT, TEXT, TEXT) TO chatledger_provisioner;
    ELSE
        RAISE NOTICE 'role chatledger_provisioner does not exist; provisioning is unavailable until it is created and granted';
    END IF;
END;
$$;

-- ── 4. Privileges for the application role ─────────────────────────────────
--
-- Login needs to read users (RLS limits it to the current tenant) and nothing more:
-- no INSERT, UPDATE or DELETE on app_user or tenant, so the running application can
-- neither create a tenant or a user nor change a role. audit_log: insert and select.
GRANT SELECT ON tenant, app_user TO chatledger_app;
GRANT SELECT, INSERT ON audit_log TO chatledger_app;
