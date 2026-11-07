-- Migration V014: order ingestion (task 4).
--
--   1. catalog_item: the tenant's price list, used to compute a missing total (requirement 1.2).
--      The application role can only READ it; it is filled by an operator or the demo seed (task 9).
--   2. orders: total_minor may be NULL, but only for UNPARSED and NEEDS_REVIEW, and never for any other
--      status. currency stays NOT NULL (an unparsed order stores the tenant's currency). order_ref and
--      delivery_note are the two fields of requirement 1.1 that had no column.
--   3. order_line: an unknown price is NULL, never 0.
--   4. Format and length CHECKs on every column that receives text from a message. The tables already
--      exist and may hold rows from before, so the checks on existing tables are NOT VALID: they apply
--      to every new and changed row, and VALIDATE CONSTRAINT can be run once old data is known to fit.
--   5. The application role may INSERT orders, order lines and customers. It still cannot UPDATE or
--      DELETE them; changing an order's status is a separate, named grant in task 6.
--
-- Requirement: 1, 8, 9. Task 4.
-- ── 1. Catalog ─────────────────────────────────────────────────────────────
--
-- name_key is the accent-insensitive, lower-case comparison key of an item name (TextKeys.key in the
-- extraction module). A lookup is an equality on (tenant_id, name_key): never LIKE.
CREATE TABLE catalog_item (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenant(id),
    name_key     TEXT        NOT NULL,
    display_name TEXT        NOT NULL,
    unit_minor   BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    disabled_at  TIMESTAMPTZ,
    CONSTRAINT uq_catalog_item_key_per_tenant UNIQUE (tenant_id, name_key),
    CONSTRAINT chk_catalog_item_key   CHECK (char_length(name_key) BETWEEN 1 AND 100),
    CONSTRAINT chk_catalog_item_name  CHECK (char_length(display_name) BETWEEN 1 AND 100),
    CONSTRAINT chk_catalog_item_price CHECK (unit_minor BETWEEN 1 AND 1000000000000)
);
CREATE INDEX idx_catalog_item_tenant ON catalog_item (tenant_id);
ALTER TABLE catalog_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog_item
    FOR ALL TO chatledger_app
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
-- ── 2. orders ──────────────────────────────────────────────────────────────
ALTER TABLE orders ALTER COLUMN total_minor DROP NOT NULL;
ALTER TABLE orders ADD COLUMN order_ref     TEXT;
ALTER TABLE orders ADD COLUMN delivery_note TEXT;
ALTER TABLE orders ADD CONSTRAINT chk_orders_ref
    CHECK (order_ref IS NULL OR order_ref ~ '^[A-Za-z0-9._:-]{1,64}$');
ALTER TABLE orders ADD CONSTRAINT chk_orders_delivery_note
    CHECK (delivery_note IS NULL OR char_length(delivery_note) BETWEEN 1 AND 300);
-- total_minor: NULL exactly when the order has no usable total.
--   UNPARSED:     always NULL
--   NEEDS_REVIEW: NULL or a valid amount (the message gave a total, or the prices could not be computed)
--   any other:    a valid amount, never NULL
ALTER TABLE orders ADD CONSTRAINT chk_orders_total_by_status CHECK (
       (status = 'UNPARSED' AND total_minor IS NULL)
    OR (status = 'NEEDS_REVIEW' AND (total_minor IS NULL OR total_minor BETWEEN 1 AND 1000000000000))
    OR (status NOT IN ('UNPARSED', 'NEEDS_REVIEW') AND total_minor IS NOT NULL AND total_minor BETWEEN 1 AND 1000000000000)
) NOT VALID;
-- (IS NOT NULL is written out on purpose: a CHECK that evaluates to unknown passes, so BETWEEN alone would let NULL through.)
-- CHAR(3) pads short values with spaces, so 'XA' would be accepted without this.
ALTER TABLE orders ADD CONSTRAINT chk_orders_currency
    CHECK (currency ~ '^[A-Z]{3}$') NOT VALID;
ALTER TABLE orders ADD CONSTRAINT chk_orders_external_id_format
    CHECK (external_id ~ '^[A-Za-z0-9._:-]{1,128}$') NOT VALID;
-- No control character other than tab and line breaks. NUL cannot be stored by PostgreSQL text at all.
ALTER TABLE orders ADD CONSTRAINT chk_orders_raw_text
    CHECK (char_length(raw_text) BETWEEN 1 AND 4000 AND raw_text !~ '[\x01-\x08\x0B\x0C\x0E-\x1F\x7F-\x9F]') NOT VALID;
-- ── 3. order_line ──────────────────────────────────────────────────────────
ALTER TABLE order_line ALTER COLUMN unit_minor DROP NOT NULL;
ALTER TABLE order_line ADD CONSTRAINT chk_order_line_unit
    CHECK (unit_minor IS NULL OR unit_minor BETWEEN 1 AND 1000000000000) NOT VALID;
ALTER TABLE order_line ADD CONSTRAINT chk_order_line_label
    CHECK (char_length(label) BETWEEN 1 AND 100) NOT VALID;
ALTER TABLE order_line ADD CONSTRAINT chk_order_line_qty_max
    CHECK (qty <= 9999) NOT VALID;
-- ── 4. customer ────────────────────────────────────────────────────────────
ALTER TABLE customer ADD CONSTRAINT chk_customer_name
    CHECK (char_length(display_name) BETWEEN 1 AND 100) NOT VALID;
ALTER TABLE customer ADD CONSTRAINT chk_customer_phone_hash
    CHECK (phone_hash IS NULL OR phone_hash ~ '^[0-9a-f]{64}$') NOT VALID;
ALTER TABLE customer ADD CONSTRAINT chk_customer_phone_last4
    CHECK (phone_last4 IS NULL OR phone_last4 ~ '^[0-9]{4}$') NOT VALID;
ALTER TABLE customer ADD CONSTRAINT chk_customer_phone_pair
    CHECK ((phone_hash IS NULL) = (phone_last4 IS NULL)) NOT VALID;
-- V003 said "BCrypt/Argon2 hash with per-tenant salt". An applied migration is never edited, so the
-- correction is recorded here. The hash is an HMAC-SHA-256 under a per-tenant key derived from app.secret
-- (so the key is not in the database); the phone itself is never stored.
COMMENT ON COLUMN customer.phone_hash IS
    'HMAC-SHA-256 (hex) of the canonical phone digits under a per-tenant key derived from app.secret. Never the number.';
-- ── 5. Privileges for the application role ─────────────────────────────────
--
-- Explicit grants only (no default privileges). INSERT, no UPDATE, no DELETE: an ingested order cannot be
-- changed or removed by the running application. The catalog is read-only.
GRANT INSERT ON orders, order_line, customer TO chatledger_app;
GRANT SELECT ON catalog_item TO chatledger_app;
