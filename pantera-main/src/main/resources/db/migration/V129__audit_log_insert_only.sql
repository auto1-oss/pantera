-- T-S04: harden the existing audit_log table for SOC2 / ISO 27001 compliance.
--
-- 1. Extend the schema with the columns the AuditEvent record carries that
--    the V100 schema did not have: `details` (JSONB), `success` (boolean),
--    `ip_address` (text). The pre-existing columns (`created_at`, `actor`,
--    `action`, `resource_type`, `resource_name`, `old_value`, `new_value`)
--    remain — `new_value` continues to be written by AuditLogDao for
--    settings-table mutations; the new `details` column carries the
--    AuditEvent-shaped structured payload.
--
-- 2. Add `BEFORE UPDATE` / `BEFORE DELETE` triggers that raise an exception
--    so audit rows cannot be mutated or removed once written. This is the
--    compliance-required immutability contract for the audit dataset.
--    A future retention process (archival to cold storage) would lift the
--    trigger temporarily — that requires SUPERUSER and is an explicit,
--    audited operation.

ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS details      JSONB,
    ADD COLUMN IF NOT EXISTS success      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS ip_address   TEXT;

CREATE INDEX IF NOT EXISTS idx_audit_log_action      ON audit_log (action);
CREATE INDEX IF NOT EXISTS idx_audit_log_success     ON audit_log (success);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_time  ON audit_log (actor, created_at DESC);

-- Immutability triggers. The function name is namespaced so other tables
-- can attach the same guard if they need write-once semantics later.

CREATE OR REPLACE FUNCTION audit_log_block_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log rows are immutable (operation=% on id=%)',
        TG_OP, OLD.id
        USING ERRCODE = 'feature_not_supported';
END;
$$;

DROP TRIGGER IF EXISTS audit_log_no_update ON audit_log;
CREATE TRIGGER audit_log_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_block_mutation();

DROP TRIGGER IF EXISTS audit_log_no_delete ON audit_log;
CREATE TRIGGER audit_log_no_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_block_mutation();

COMMENT ON TABLE audit_log IS 'T-S04: write-once audit trail for admin actions. UPDATE / DELETE raise exceptions via the audit_log_block_mutation() trigger.';
