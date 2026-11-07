-- Migration V010: tenancy integrity.
--
-- Prepares the schema for row-level security (V011) and fixes cross-tenant
-- references that foreign keys cannot see. PostgreSQL foreign-key checks bypass
-- row-level security, so a single-column foreign key accepts another tenant's
-- id if the caller knows the UUID. Every foreign key between tenant tables
-- therefore becomes a composite key that includes tenant_id, as V009 already did
-- for reversals.
--
-- V001-V009 are not edited. Nothing here touches ledger_entry, ledger_txn or
-- account data, so it works with the immutability triggers.
--
-- Requirement: 9.1 (isolate every query by tenant), 4.3. Task 3.1.

-- ── 1. Tenant: public workspace code and a disabled flag ────────────────────
--
-- slug is the workspace code typed on the login form. It is an identifier, not
-- a secret. Existing rows (if any) get a derived code so the column can be NOT NULL.
ALTER TABLE tenant ADD COLUMN slug TEXT;
ALTER TABLE tenant ADD COLUMN disabled_at TIMESTAMPTZ;

UPDATE tenant SET slug = 'tenant-' || substr(replace(id::text, '-', ''), 1, 8) WHERE slug IS NULL;

ALTER TABLE tenant ALTER COLUMN slug SET NOT NULL;
ALTER TABLE tenant ADD CONSTRAINT uq_tenant_slug UNIQUE (slug);
ALTER TABLE tenant ADD CONSTRAINT chk_tenant_slug_format
    CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$'
           AND slug NOT IN ('admin', 'api', 'login', 'logout', 'static', 'health'));

-- ── 2. Users: normalised email and a disabled flag ──────────────────────────
UPDATE app_user SET email = lower(btrim(email)) WHERE email <> lower(btrim(email));

ALTER TABLE app_user ADD COLUMN disabled_at TIMESTAMPTZ;
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_email_normalised
    CHECK (email = lower(btrim(email)) AND char_length(email) BETWEEN 3 AND 254);

-- ── 3. Composite keys that foreign keys can target ──────────────────────────
ALTER TABLE customer ADD CONSTRAINT uq_customer_id_tenant UNIQUE (id, tenant_id);
ALTER TABLE orders   ADD CONSTRAINT uq_orders_id_tenant   UNIQUE (id, tenant_id);
ALTER TABLE payment  ADD CONSTRAINT uq_payment_id_tenant  UNIQUE (id, tenant_id);
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_id_tenant UNIQUE (id, tenant_id);

-- ── 4. order_line and match get their own tenant_id ─────────────────────────
--
-- Both tables are mutable and small, so they can be back-filled. A tenant_id on
-- the row itself also keeps the row-level-security policy a plain comparison.
ALTER TABLE order_line ADD COLUMN tenant_id UUID;
UPDATE order_line ol SET tenant_id = o.tenant_id FROM orders o WHERE o.id = ol.order_id;
ALTER TABLE order_line ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE match ADD COLUMN tenant_id UUID;
UPDATE match m SET tenant_id = o.tenant_id FROM orders o WHERE o.id = m.order_id;
ALTER TABLE match ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX idx_order_line_tenant ON order_line (tenant_id);
CREATE INDEX idx_match_tenant      ON match (tenant_id);

-- ── 5. Replace single-column foreign keys with tenant-aware ones ────────────
--
-- ON DELETE CASCADE on order_line is kept. Foreign-key actions run with the
-- owner's rights and are not affected by row-level security.
ALTER TABLE orders DROP CONSTRAINT orders_customer_id_fkey;
ALTER TABLE orders ADD CONSTRAINT fk_orders_customer_same_tenant
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customer (id, tenant_id);

ALTER TABLE order_line DROP CONSTRAINT order_line_order_id_fkey;
ALTER TABLE order_line ADD CONSTRAINT fk_order_line_order_same_tenant
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id) ON DELETE CASCADE;

ALTER TABLE match DROP CONSTRAINT match_order_id_fkey;
ALTER TABLE match DROP CONSTRAINT match_payment_id_fkey;
ALTER TABLE match DROP CONSTRAINT match_decided_by_fkey;
ALTER TABLE match ADD CONSTRAINT fk_match_order_same_tenant
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id);
ALTER TABLE match ADD CONSTRAINT fk_match_payment_same_tenant
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payment (id, tenant_id);
ALTER TABLE match ADD CONSTRAINT fk_match_decided_by_same_tenant
    FOREIGN KEY (decided_by, tenant_id) REFERENCES app_user (id, tenant_id);

ALTER TABLE audit_log DROP CONSTRAINT audit_log_actor_fkey;
ALTER TABLE audit_log ADD CONSTRAINT fk_audit_log_actor_same_tenant
    FOREIGN KEY (actor, tenant_id) REFERENCES app_user (id, tenant_id);

-- ── 6. Privileges for the application role ──────────────────────────────────
--
-- Restated so this migration shows what the role may do with the tables it
-- touched: read only. The new columns are covered by the table-level grants.
-- There are no default privileges, and the role owns none of these tables.
GRANT SELECT ON tenant, app_user, customer, orders, order_line, payment, match, audit_log
    TO chatledger_app;
