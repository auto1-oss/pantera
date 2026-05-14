-- T-S03: trusted PGP keyring for Maven .asc verification.
--
-- Admins upload ASCII-armored public keys via a REST endpoint (T-S03
-- follow-up). Maven proxies with `verifyPgp: true` consult this table
-- to resolve the long key id from each .asc signature to a trusted
-- key. Verification failure (no row, or signature does not validate)
-- rejects the primary with 403 and writes a `pgp_verification_failed`
-- audit event.
--
-- key_id_hex is the 64-bit OpenPGP long key id rendered as a 16-char
-- uppercase hex string (e.g. `DEADBEEF12345678`). This avoids the
-- signed/unsigned long quirks of storing the id numerically and keeps
-- the lookup SQL type-stable across drivers.
--
-- fingerprint is the SHA-1 fingerprint of the key, 40 hex chars.
-- Used for admin display + audit only; lookups always go via
-- key_id_hex.

CREATE TABLE IF NOT EXISTS pgp_keyring (
    id                  BIGSERIAL PRIMARY KEY,
    key_id_hex          CHAR(16)    NOT NULL UNIQUE,
    fingerprint         CHAR(40)    NOT NULL UNIQUE,
    public_key_armored  TEXT        NOT NULL,
    uploaded_by         VARCHAR(255) NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    description         TEXT
);

CREATE INDEX IF NOT EXISTS idx_pgp_keyring_uploaded_at
    ON pgp_keyring (uploaded_at DESC);

CREATE INDEX IF NOT EXISTS idx_pgp_keyring_uploaded_by
    ON pgp_keyring (uploaded_by);

COMMENT ON TABLE pgp_keyring IS 'T-S03: admin-uploaded trusted PGP public keys for Maven .asc verification.';
COMMENT ON COLUMN pgp_keyring.key_id_hex IS '64-bit OpenPGP long key id rendered as 16-char uppercase hex; lookups go through here.';
COMMENT ON COLUMN pgp_keyring.fingerprint IS 'SHA-1 fingerprint (40 hex chars) for admin UI display + audit.';
COMMENT ON COLUMN pgp_keyring.public_key_armored IS 'Full ASCII-armored PGP public key block exactly as uploaded.';
