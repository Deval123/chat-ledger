-- Migration V017: an index for the matcher's one lookup in the audit trail.
--
-- The matcher reads who recorded a payment (a seller, the machine feed, or a helper) from the append-only audit entry written when the
-- payment was ingested ('PAYMENT_INGESTED', subject = the payment id), never from a caller. It does this for each candidate payment, so the
-- lookup by (tenant, subject) is indexed. A partial index: only the entries it is for.
--
-- Requirement: 3. Task 6.2.
CREATE INDEX idx_audit_log_payment_ingested ON audit_log (tenant_id, subject) WHERE action = 'PAYMENT_INGESTED';
