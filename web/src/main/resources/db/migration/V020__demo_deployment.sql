-- Migration V020: the demo deployment (task 9).
--
-- A demo runs on its own database. Two switches, both off by default, must agree before a visitor can enter: the configuration
-- (app.demo.enabled) and this database's deployment mode. In a DEMO database no real workspace can be provisioned and password sign-in
-- reaches no demo workspace; in a REGULAR database every demo function refuses. Nothing here deletes a row or weakens a trigger: a demo
-- workspace is retired (disabled, personal data overwritten) and a fresh one takes its place.
--
--   1. deployment_mode: one row, REGULAR until the demo operator's demo_init moves it to DEMO (never back).
--   2. tenant.kind: REGULAR or DEMO, tied to the slug prefix demo-, and frozen with the slug.
--   3. demo_tenant: the pool (SEEDING, READY, CLAIMED, RETIRED) and each workspace's lease.
--   4. A row cap for demo workspaces: at most demo_row_cap() orders and payments together.
--   5. Password sign-in refuses demo workspaces; provisioning refuses on a demo database and on demo workspaces.
--   6. The demo functions (SECURITY DEFINER, fixed search_path, one grantee each) and their grants.
--
-- Requirements: 7, 9.1. Task 9 (design stop D1 to D25).

-- ── 1. Deployment mode ─────────────────────────────────────────────────────
CREATE TABLE deployment_mode (
    id         SMALLINT    PRIMARY KEY CHECK (id = 1),
    mode       TEXT        NOT NULL CHECK (mode IN ('REGULAR', 'DEMO')),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO deployment_mode (id, mode) VALUES (1, 'REGULAR');
-- No policy: the application cannot read or write it. It reads the mode through demo_mode().
ALTER TABLE deployment_mode ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON deployment_mode FROM PUBLIC;

-- The row exists once and moves only from REGULAR to DEMO, for every role that cannot alter triggers.
CREATE OR REPLACE FUNCTION deployment_mode_guard()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'the deployment mode row cannot be added, deleted or truncated' USING ERRCODE = 'check_violation';
    END IF;
    IF NOT (OLD.mode = 'REGULAR' AND NEW.mode = 'DEMO') OR NEW.id <> OLD.id THEN
        RAISE EXCEPTION 'the deployment mode only moves from REGULAR to DEMO' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_deployment_mode_guard
    BEFORE INSERT OR UPDATE OR DELETE ON deployment_mode
    FOR EACH ROW EXECUTE FUNCTION deployment_mode_guard();
CREATE TRIGGER trg_deployment_mode_no_truncate
    BEFORE TRUNCATE ON deployment_mode
    FOR EACH STATEMENT EXECUTE FUNCTION deployment_mode_guard();

-- ── 2. Tenant kind ─────────────────────────────────────────────────────────
-- A slug already starting with demo- on a regular workspace makes this migration fail, on purpose: it is refused, never guessed.
ALTER TABLE tenant ADD COLUMN kind TEXT NOT NULL DEFAULT 'REGULAR';
ALTER TABLE tenant ADD CONSTRAINT chk_tenant_kind
    CHECK (kind IN ('REGULAR', 'DEMO') AND (kind = 'DEMO') = (slug LIKE 'demo-%'));

CREATE OR REPLACE FUNCTION tenant_identity_frozen()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind IS DISTINCT FROM OLD.kind OR NEW.slug IS DISTINCT FROM OLD.slug THEN
        RAISE EXCEPTION 'the kind and the workspace code of tenant % cannot change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_tenant_identity_frozen
    BEFORE UPDATE OF kind, slug ON tenant
    FOR EACH ROW EXECUTE FUNCTION tenant_identity_frozen();

-- ── 3. The pool ────────────────────────────────────────────────────────────
-- A demo row can only name a demo workspace: the key includes the tenant's kind, which cannot change.
ALTER TABLE tenant ADD CONSTRAINT uq_tenant_id_kind UNIQUE (id, kind);
CREATE TABLE demo_tenant (
    tenant_id     UUID        PRIMARY KEY,
    tenant_kind   TEXT        NOT NULL DEFAULT 'DEMO' CHECK (tenant_kind = 'DEMO'),
    seller_id     UUID        NOT NULL,
    state         TEXT        NOT NULL CHECK (state IN ('SEEDING', 'READY', 'CLAIMED', 'RETIRED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at      TIMESTAMPTZ,
    claimed_at    TIMESTAMPTZ,
    lease_ends_at TIMESTAMPTZ,
    retired_at    TIMESTAMPTZ,
    CONSTRAINT fk_demo_tenant_is_demo FOREIGN KEY (tenant_id, tenant_kind) REFERENCES tenant (id, kind),
    CONSTRAINT fk_demo_tenant_seller_same_tenant FOREIGN KEY (seller_id, tenant_id) REFERENCES app_user (id, tenant_id),
    CONSTRAINT chk_demo_tenant_state CHECK (
           (state = 'SEEDING' AND ready_at IS NULL AND claimed_at IS NULL AND lease_ends_at IS NULL AND retired_at IS NULL)
        OR (state = 'READY' AND ready_at IS NOT NULL AND claimed_at IS NULL AND lease_ends_at IS NULL AND retired_at IS NULL)
        OR (state = 'CLAIMED' AND ready_at IS NOT NULL AND claimed_at IS NOT NULL AND lease_ends_at > claimed_at AND retired_at IS NULL)
        OR (state = 'RETIRED' AND retired_at IS NOT NULL))
);
CREATE INDEX idx_demo_tenant_ready ON demo_tenant (ready_at, tenant_id) WHERE state = 'READY';
ALTER TABLE demo_tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON demo_tenant FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
REVOKE ALL ON demo_tenant FROM PUBLIC;

-- Forward only (SEEDING, READY, CLAIMED, then RETIRED from any of them); the identity columns never change; never deleted.
CREATE OR REPLACE FUNCTION demo_tenant_forward_only()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'demo workspaces are retired, never deleted' USING ERRCODE = 'check_violation';
    END IF;
    -- (tenant_kind needs no test here: its CHECK keeps it DEMO.)
    IF (NEW.tenant_id, NEW.seller_id, NEW.created_at) IS DISTINCT FROM (OLD.tenant_id, OLD.seller_id, OLD.created_at) THEN
        RAISE EXCEPTION 'demo workspace % cannot change its identity', OLD.tenant_id USING ERRCODE = 'check_violation';
    END IF;
    IF NOT ((OLD.state = 'SEEDING' AND NEW.state = 'READY')
         OR (OLD.state = 'READY' AND NEW.state = 'CLAIMED')
         OR (OLD.state <> 'RETIRED' AND NEW.state = 'RETIRED')) THEN
        RAISE EXCEPTION 'demo workspace % cannot move from % to %', OLD.tenant_id, OLD.state, NEW.state USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_demo_tenant_forward_only
    BEFORE UPDATE OR DELETE ON demo_tenant
    FOR EACH ROW EXECUTE FUNCTION demo_tenant_forward_only();
CREATE TRIGGER trg_demo_tenant_no_truncate
    BEFORE TRUNCATE ON demo_tenant
    FOR EACH STATEMENT EXECUTE FUNCTION demo_tenant_forward_only();

-- ── 4. Row cap ─────────────────────────────────────────────────────────────
-- One number for the trigger and for the application's own check (which answers with a sentence before the insert is tried).
CREATE OR REPLACE FUNCTION demo_row_cap()
    RETURNS INTEGER
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT 300
$$;
REVOKE ALL ON FUNCTION demo_row_cap() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION demo_row_cap() TO chatledger_app;

-- Runs as the inserting role (the application, under row-level security, or the owner in tests). Regular workspaces pay one lookup of their
-- own tenant row. Demo inserts of one workspace are serialised by a transaction lock, so two at once cannot both pass at the cap.
CREATE OR REPLACE FUNCTION demo_row_cap_check()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM tenant t WHERE t.id = NEW.tenant_id AND t.kind = 'DEMO') THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('demo-row-cap:' || NEW.tenant_id::text, 0));
        IF (SELECT count(*) FROM orders o WHERE o.tenant_id = NEW.tenant_id)
         + (SELECT count(*) FROM payment p WHERE p.tenant_id = NEW.tenant_id) >= demo_row_cap() THEN
            RAISE EXCEPTION 'this demo workspace is full' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_orders_demo_row_cap
    BEFORE INSERT ON orders
    FOR EACH ROW EXECUTE FUNCTION demo_row_cap_check();
CREATE TRIGGER trg_payment_demo_row_cap
    BEFORE INSERT ON payment
    FOR EACH ROW EXECUTE FUNCTION demo_row_cap_check();

-- ── 5. Sign-in and provisioning ────────────────────────────────────────────
-- Password sign-in reaches regular workspaces only. A demo workspace is entered only through the demo button (demo_claim).
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
       AND t.kind = 'REGULAR'
$$;

-- No real workspace on a demo database. The row lock orders this against demo_init, so the two cannot both pass.
CREATE OR REPLACE FUNCTION provision_tenant(p_slug TEXT, p_name TEXT, p_email TEXT, p_password_hash TEXT)
    RETURNS TABLE (out_tenant_id UUID, out_user_id UUID)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_tenant UUID := pg_catalog.gen_random_uuid();
    v_user   UUID := pg_catalog.gen_random_uuid();
    v_mode   TEXT;
BEGIN
    SELECT m.mode INTO v_mode FROM public.deployment_mode m WHERE m.id = 1 FOR SHARE;
    IF v_mode IS DISTINCT FROM 'REGULAR' THEN
        RAISE EXCEPTION 'workspaces cannot be provisioned on a demo deployment' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
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

-- A user only in an enabled regular workspace: nobody can be given a password to a demo workspace.
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
     WHERE t.slug = p_slug AND t.disabled_at IS NULL AND t.kind = 'REGULAR';
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'workspace not found' USING ERRCODE = 'no_data_found';
    END IF;

    INSERT INTO public.app_user (id, tenant_id, email, password_hash, role)
    VALUES (v_user, v_tenant, p_email, p_password_hash, p_role);

    RETURN v_user;
END;
$$;

-- ── 6. Demo functions ──────────────────────────────────────────────────────
-- Each one checks the mode first, in its own transaction, and touches demo workspaces only.

-- The application's view of the mode (its startup check).
CREATE OR REPLACE FUNCTION demo_mode()
    RETURNS TEXT
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT m.mode FROM public.deployment_mode m WHERE m.id = 1
$$;

-- Once, on a database with no regular workspace. Calling it again on a demo database changes nothing.
CREATE OR REPLACE FUNCTION demo_init()
    RETURNS TEXT
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_mode TEXT;
BEGIN
    SELECT m.mode INTO v_mode FROM public.deployment_mode m WHERE m.id = 1 FOR UPDATE;
    IF v_mode = 'DEMO' THEN
        RETURN 'DEMO';
    END IF;
    IF EXISTS (SELECT 1 FROM public.tenant t WHERE t.kind = 'REGULAR') THEN
        RAISE EXCEPTION 'a demo deployment must hold no regular workspace' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    UPDATE public.deployment_mode SET mode = 'DEMO', changed_at = pg_catalog.now() WHERE id = 1;
    RETURN 'DEMO';
END;
$$;

-- A new workspace in state SEEDING, with its seller (whose password nobody knows: only its hash arrives here), unless the pool already holds
-- p_pool_size workspaces that are seeding or ready. Returns no row then.
CREATE OR REPLACE FUNCTION demo_create_tenant(p_password_hash TEXT, p_pool_size INTEGER)
    RETURNS TABLE (out_tenant_id UUID, out_user_id UUID)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_mode   TEXT;
    v_tenant UUID := pg_catalog.gen_random_uuid();
    v_user   UUID := pg_catalog.gen_random_uuid();
    v_slug   TEXT := 'demo-' || pg_catalog.substr(pg_catalog.replace(pg_catalog.gen_random_uuid()::text, '-', ''), 1, 12);
BEGIN
    SELECT m.mode INTO v_mode FROM public.deployment_mode m WHERE m.id = 1 FOR SHARE;
    IF v_mode IS DISTINCT FROM 'DEMO' THEN
        RAISE EXCEPTION 'not a demo deployment' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF p_pool_size IS NULL OR p_pool_size NOT BETWEEN 10 AND 50 THEN
        RAISE EXCEPTION 'the pool size must be between 10 and 50' USING ERRCODE = 'check_violation';
    END IF;
    -- One creator at a time, so the count below cannot be passed by two at once.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('demo-pool', 0));
    IF (SELECT pg_catalog.count(*) FROM public.demo_tenant d WHERE d.state IN ('SEEDING', 'READY')) >= p_pool_size THEN
        RETURN;
    END IF;

    INSERT INTO public.tenant (id, name, currency, scale, slug, kind, timezone)
    VALUES (v_tenant, 'Demo shop', 'XAF', 0, v_slug, 'DEMO', 'Africa/Douala');
    INSERT INTO public.app_user (id, tenant_id, email, password_hash, role)
    VALUES (v_user, v_tenant, 'seller@' || v_slug || '.demo.invalid', p_password_hash, 'SELLER');
    INSERT INTO public.demo_tenant (tenant_id, seller_id, state) VALUES (v_tenant, v_user, 'SEEDING');

    RETURN QUERY SELECT v_tenant, v_user;
END;
$$;

-- The catalog of a workspace that is being seeded: a JSON array of {name_key, display_name, unit_minor}, at most 50. The table's own CHECKs
-- bound each value. Returns the rows written (a repeat writes none).
CREATE OR REPLACE FUNCTION demo_seed_catalog(p_tenant UUID, p_items JSONB)
    RETURNS INTEGER
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_mode    TEXT;
    v_written INTEGER;
BEGIN
    SELECT m.mode INTO v_mode FROM public.deployment_mode m WHERE m.id = 1 FOR SHARE;
    IF v_mode IS DISTINCT FROM 'DEMO' THEN
        RAISE EXCEPTION 'not a demo deployment' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.demo_tenant d JOIN public.tenant t ON t.id = d.tenant_id
                    WHERE d.tenant_id = p_tenant AND d.state = 'SEEDING' AND t.kind = 'DEMO') THEN
        RAISE EXCEPTION 'not a demo workspace that is being seeded' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF p_items IS NULL OR pg_catalog.jsonb_typeof(p_items) <> 'array'
       OR pg_catalog.jsonb_array_length(p_items) NOT BETWEEN 1 AND 50 THEN
        RAISE EXCEPTION 'the catalog must be an array of 1 to 50 items' USING ERRCODE = 'check_violation';
    END IF;
    INSERT INTO public.catalog_item (tenant_id, name_key, display_name, unit_minor)
    SELECT p_tenant, i ->> 'name_key', i ->> 'display_name', (i ->> 'unit_minor')::BIGINT
      FROM pg_catalog.jsonb_array_elements(p_items) AS i
    ON CONFLICT (tenant_id, name_key) DO NOTHING;
    GET DIAGNOSTICS v_written = ROW_COUNT;
    RETURN v_written;
END;
$$;

-- SEEDING to READY, only when the seed wrote at least the orders and payments it expected.
CREATE OR REPLACE FUNCTION demo_mark_ready(p_tenant UUID, p_orders INTEGER, p_payments INTEGER)
    RETURNS BOOLEAN
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_mode TEXT;
BEGIN
    SELECT m.mode INTO v_mode FROM public.deployment_mode m WHERE m.id = 1 FOR SHARE;
    IF v_mode IS DISTINCT FROM 'DEMO' THEN
        RAISE EXCEPTION 'not a demo deployment' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF p_orders IS NULL OR p_payments IS NULL OR p_orders < 1 OR p_payments < 1 THEN
        RAISE EXCEPTION 'the expected counts must be positive' USING ERRCODE = 'check_violation';
    END IF;
    IF (SELECT pg_catalog.count(*) FROM public.orders o WHERE o.tenant_id = p_tenant) < p_orders
       OR (SELECT pg_catalog.count(*) FROM public.payment p WHERE p.tenant_id = p_tenant) < p_payments THEN
        RETURN FALSE;
    END IF;
    UPDATE public.demo_tenant d SET state = 'READY', ready_at = pg_catalog.now()
     WHERE d.tenant_id = p_tenant AND d.state = 'SEEDING'
       AND EXISTS (SELECT 1 FROM public.tenant t WHERE t.id = d.tenant_id AND t.kind = 'DEMO' AND t.disabled_at IS NULL);
    RETURN FOUND;
END;
$$;

-- The application's only demo write: hand out the oldest ready workspace that is fresh enough (seeded at most three hours ago), for
-- p_lease. (A READY workspace is never disabled: demo_retire disables and retires in one transaction, and the state never goes back.) Returns no row if none is ready. SKIP LOCKED: two visitors at once get two workspaces, never the same one.
CREATE OR REPLACE FUNCTION demo_claim(p_lease INTERVAL)
    RETURNS TABLE (out_tenant_id UUID, out_user_id UUID)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_mode TEXT;
BEGIN
    SELECT m.mode INTO v_mode FROM public.deployment_mode m WHERE m.id = 1;
    IF v_mode IS DISTINCT FROM 'DEMO' THEN
        RETURN;
    END IF;
    IF p_lease IS NULL OR p_lease < INTERVAL '10 minutes' OR p_lease > INTERVAL '4 hours' THEN
        RAISE EXCEPTION 'the lease must be between 10 minutes and 4 hours' USING ERRCODE = 'check_violation';
    END IF;
    RETURN QUERY
    UPDATE public.demo_tenant d
       SET state = 'CLAIMED', claimed_at = pg_catalog.now(), lease_ends_at = pg_catalog.now() + p_lease
     WHERE d.tenant_id = (SELECT r.tenant_id
                            FROM public.demo_tenant r JOIN public.tenant t ON t.id = r.tenant_id
                           WHERE r.state = 'READY' AND t.kind = 'DEMO'
                             AND r.ready_at > pg_catalog.now() - INTERVAL '3 hours'
                           ORDER BY r.ready_at, r.tenant_id
                           LIMIT 1
                             FOR UPDATE OF r SKIP LOCKED)
    RETURNING d.tenant_id, d.seller_id;
END;
$$;

-- The workspaces to retire now: leases that have ended, ready ones older than p_max_ready, seeds stuck for longer than p_max_seeding.
CREATE OR REPLACE FUNCTION demo_due_for_retirement(p_max_ready INTERVAL, p_max_seeding INTERVAL)
    RETURNS SETOF UUID
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_mode TEXT;
BEGIN
    SELECT m.mode INTO v_mode FROM public.deployment_mode m WHERE m.id = 1;
    IF v_mode IS DISTINCT FROM 'DEMO' THEN
        RAISE EXCEPTION 'not a demo deployment' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN QUERY
    SELECT d.tenant_id FROM public.demo_tenant d
     WHERE (d.state = 'CLAIMED' AND d.lease_ends_at <= pg_catalog.now())
        OR (d.state = 'READY' AND d.ready_at <= pg_catalog.now() - p_max_ready)
        OR (d.state = 'SEEDING' AND d.created_at <= pg_catalog.now() - p_max_seeding)
     ORDER BY d.created_at, d.tenant_id;
END;
$$;

-- Retire one demo workspace: disabled (its sessions end at the next status check), its personal data overwritten. Nothing is deleted and
-- the ledger, the audit log and the flags are not touched: they hold ids, amounts, codes and fixed sentences only. Each overwritten value
-- satisfies its column's constraints (checked against V004, V014 and V015). Returns false if it was already retired.
CREATE OR REPLACE FUNCTION demo_retire(p_tenant UUID)
    RETURNS BOOLEAN
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_mode    TEXT;
    v_state   TEXT;
    v_removed CONSTANT TEXT := 'Removed when the demo ended.';
BEGIN
    SELECT m.mode INTO v_mode FROM public.deployment_mode m WHERE m.id = 1 FOR SHARE;
    IF v_mode IS DISTINCT FROM 'DEMO' THEN
        RAISE EXCEPTION 'not a demo deployment' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    SELECT d.state INTO v_state
      FROM public.demo_tenant d JOIN public.tenant t ON t.id = d.tenant_id
     WHERE d.tenant_id = p_tenant AND t.kind = 'DEMO'
       FOR UPDATE OF d;
    IF v_state IS NULL THEN
        RAISE EXCEPTION 'not a demo workspace' USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF v_state = 'RETIRED' THEN
        RETURN FALSE;
    END IF;

    UPDATE public.tenant SET disabled_at = pg_catalog.now() WHERE id = p_tenant AND kind = 'DEMO' AND disabled_at IS NULL;
    UPDATE public.demo_tenant SET state = 'RETIRED', retired_at = pg_catalog.now() WHERE tenant_id = p_tenant;

    UPDATE public.orders SET raw_text = v_removed, delivery_note = NULL WHERE tenant_id = p_tenant;
    UPDATE public.order_line SET label = 'Removed' WHERE tenant_id = p_tenant;
    UPDATE public.customer SET display_name = 'Removed', phone_hash = NULL, phone_last4 = NULL WHERE tenant_id = p_tenant;
    UPDATE public.payment SET raw_text = v_removed, sender_name = NULL, sender_phone_hash = NULL, sender_last4 = NULL
     WHERE tenant_id = p_tenant;
    RETURN TRUE;
END;
$$;

-- One refresh at a time: a session-level lock on the caller's connection, released when that connection closes.
CREATE OR REPLACE FUNCTION demo_refresh_lock()
    RETURNS BOOLEAN
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT pg_catalog.pg_try_advisory_lock(pg_catalog.hashtextextended('demo-refresh', 0))
$$;

-- ── Grants ─────────────────────────────────────────────────────────────────
REVOKE ALL ON FUNCTION demo_mode() FROM PUBLIC;
REVOKE ALL ON FUNCTION demo_init() FROM PUBLIC;
REVOKE ALL ON FUNCTION demo_create_tenant(TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION demo_seed_catalog(UUID, JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION demo_mark_ready(UUID, INTEGER, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION demo_claim(INTERVAL) FROM PUBLIC;
REVOKE ALL ON FUNCTION demo_due_for_retirement(INTERVAL, INTERVAL) FROM PUBLIC;
REVOKE ALL ON FUNCTION demo_retire(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION demo_refresh_lock() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION demo_mode() TO chatledger_app;
GRANT EXECUTE ON FUNCTION demo_claim(INTERVAL) TO chatledger_app;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatledger_demo_operator') THEN
        GRANT EXECUTE ON FUNCTION demo_init() TO chatledger_demo_operator;
        GRANT EXECUTE ON FUNCTION demo_create_tenant(TEXT, INTEGER) TO chatledger_demo_operator;
        GRANT EXECUTE ON FUNCTION demo_seed_catalog(UUID, JSONB) TO chatledger_demo_operator;
        GRANT EXECUTE ON FUNCTION demo_mark_ready(UUID, INTEGER, INTEGER) TO chatledger_demo_operator;
        GRANT EXECUTE ON FUNCTION demo_due_for_retirement(INTERVAL, INTERVAL) TO chatledger_demo_operator;
        GRANT EXECUTE ON FUNCTION demo_retire(UUID) TO chatledger_demo_operator;
        GRANT EXECUTE ON FUNCTION demo_refresh_lock() TO chatledger_demo_operator;
    ELSE
        RAISE NOTICE 'role chatledger_demo_operator does not exist; the demo is unavailable until it is created and granted';
    END IF;
END;
$$;
