-- Migration V015: payment ingestion (task 5).
--
--   1. payment gets what ingestion needs: external_id (idempotency, like orders), reference (the order code the payer wrote in the
--      transfer note), sender_phone_hash (keyed hash of the sender's number, same function and key as customer.phone_hash),
--      duplicate_of (the first payment with the same transaction id) and template_id (which template read the text).
--   2. An UNPARSED payment has no provider and no amount: provider and amount_minor become nullable, but only for UNPARSED
--      (a CHECK ties them to the status, like orders.total_minor). currency stays NOT NULL and is the tenant's.
--   3. Requirement 2.4 says a repeated transaction id is stored as a DUPLICATE record, so the plain UNIQUE constraint is replaced
--      by a partial unique index that leaves DUPLICATE rows out. Two non-duplicate rows with the same transaction id remain
--      impossible, even under concurrency.
--   4. Format and length CHECKs on every column that receives text from a confirmation. The table already exists and may hold
--      rows from before, so these are NOT VALID: they apply to every new and changed row.
--   5. The application role may INSERT payments. It still cannot UPDATE or DELETE them (status changes are a separate, named
--      grant in task 6).
--
-- Requirement: 2, 9. Task 5.
-- ── 1 and 2. Columns ───────────────────────────────────────────────────────
ALTER TABLE payment ADD COLUMN external_id       TEXT;
ALTER TABLE payment ADD COLUMN reference         TEXT;
ALTER TABLE payment ADD COLUMN sender_phone_hash TEXT;
ALTER TABLE payment ADD COLUMN duplicate_of      UUID;
ALTER TABLE payment ADD COLUMN template_id       TEXT;
ALTER TABLE payment ALTER COLUMN provider     DROP NOT NULL;
ALTER TABLE payment ALTER COLUMN amount_minor DROP NOT NULL;
-- The duplicate must belong to the same tenant (the composite key from V010).
ALTER TABLE payment ADD CONSTRAINT fk_payment_duplicate_of_same_tenant
    FOREIGN KEY (duplicate_of, tenant_id) REFERENCES payment (id, tenant_id);
-- Rows from before this migration have no external_id; NULLs do not collide in a unique index.
CREATE UNIQUE INDEX uq_payment_external_per_tenant ON payment (tenant_id, external_id);
COMMENT ON COLUMN payment.sender_phone_hash IS
    'HMAC-SHA-256 (hex) of the sender''s canonical phone digits under the same per-tenant key as customer.phone_hash. Never the number.';
-- ── 3. Transaction id uniqueness, DUPLICATE rows excluded ──────────────────
-- The transaction id is compared in one canonical form, upper case: the same id written in another case is the same transaction, so
-- a replay with the case changed is a DUPLICATE, not a second payment. The column keeps the id as written (and raw_text keeps the
-- whole text); only the index and the duplicate lookup fold the case. A false duplicate is the safe failure: the seller can see it.
-- Ids are ASCII (chk_payment_tx_id), so upper() is the same on every machine.
ALTER TABLE payment DROP CONSTRAINT uq_payment_txid_per_tenant_provider;
CREATE UNIQUE INDEX uq_payment_txid_original ON payment (tenant_id, provider, upper(tx_id))
    WHERE tx_id IS NOT NULL AND status <> 'DUPLICATE';
-- ── 4. Checks ──────────────────────────────────────────────────────────────
ALTER TABLE payment ADD CONSTRAINT chk_payment_currency
    CHECK (currency ~ '^[A-Z]{3}$') NOT VALID;
ALTER TABLE payment ADD CONSTRAINT chk_payment_external_id
    CHECK (external_id IS NOT NULL AND external_id ~ '^[A-Za-z0-9._:-]{1,128}$') NOT VALID;
-- UNPARSED: nothing was read, so nothing is stored but the text. Every other status: provider, a valid amount and a time.
-- (IS NOT NULL is written out on purpose: a CHECK that evaluates to unknown passes.)
ALTER TABLE payment ADD CONSTRAINT chk_payment_by_status CHECK (
       (status = 'UNPARSED' AND provider IS NULL AND amount_minor IS NULL AND tx_id IS NULL AND paid_at IS NULL
            AND duplicate_of IS NULL AND sender_name IS NULL AND sender_last4 IS NULL AND sender_phone_hash IS NULL
            AND reference IS NULL AND template_id IS NULL)
    OR (status <> 'UNPARSED' AND provider IS NOT NULL AND amount_minor IS NOT NULL
            AND amount_minor BETWEEN 1 AND 1000000000000 AND paid_at IS NOT NULL)
) NOT VALID;
-- DUPLICATE points at the payment it repeats and always has a transaction id; RECEIVED always has one; UNVERIFIED never does.
ALTER TABLE payment ADD CONSTRAINT chk_payment_status_links CHECK (
        ((status = 'DUPLICATE') = (duplicate_of IS NOT NULL))
    AND (status <> 'DUPLICATE'  OR tx_id IS NOT NULL)
    AND (status <> 'RECEIVED'   OR tx_id IS NOT NULL)
    AND (status <> 'UNVERIFIED' OR tx_id IS NULL)
) NOT VALID;
ALTER TABLE payment ADD CONSTRAINT chk_payment_tx_id
    CHECK (tx_id IS NULL OR tx_id ~ '^[A-Za-z0-9._:-]{6,40}$') NOT VALID;
ALTER TABLE payment ADD CONSTRAINT chk_payment_provider
    CHECK (provider IS NULL OR provider ~ '^[A-Z0-9_]{1,32}$') NOT VALID;
ALTER TABLE payment ADD CONSTRAINT chk_payment_template_id
    CHECK (template_id IS NULL OR template_id ~ '^[a-z0-9-]{1,64}$') NOT VALID;
ALTER TABLE payment ADD CONSTRAINT chk_payment_sender_name
    CHECK (sender_name IS NULL OR char_length(sender_name) BETWEEN 2 AND 40) NOT VALID;
-- The hash is hex of 32 bytes and always comes with the last four digits; the last four are four digits.
ALTER TABLE payment ADD CONSTRAINT chk_payment_sender CHECK (
        (sender_phone_hash IS NULL OR (sender_phone_hash ~ '^[0-9a-f]{64}$' AND sender_last4 IS NOT NULL))
    AND (sender_last4 IS NULL OR sender_last4 ~ '^[0-9]{4}$')
) NOT VALID;
-- Same format as orders.order_ref, so the two are compared with one key function.
ALTER TABLE payment ADD CONSTRAINT chk_payment_reference
    CHECK (reference IS NULL OR reference ~ '^[A-Za-z0-9._:-]{1,64}$') NOT VALID;
-- No control character other than tab and line breaks. NUL cannot be stored by PostgreSQL text at all.
ALTER TABLE payment ADD CONSTRAINT chk_payment_raw_text
    CHECK (char_length(raw_text) BETWEEN 1 AND 1000 AND raw_text !~ '[\x01-\x08\x0B\x0C\x0E-\x1F\x7F-\x9F]') NOT VALID;
-- ── 5. Privileges for the application role ─────────────────────────────────
--
-- Explicit grant only (no default privileges). INSERT, no UPDATE, no DELETE: an ingested payment cannot be changed or
-- removed by the running application.
GRANT INSERT ON payment TO chatledger_app;
