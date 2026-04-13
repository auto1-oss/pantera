-- V116: Force password change flag for bootstrap admin
--
-- Adds a boolean flag set to TRUE when the default admin user is
-- inserted on a fresh install (admin/admin). On first login Pantera
-- redirects the user to a forced password-change screen and refuses
-- to clear the flag until a sufficiently complex password is set.
--
-- Existing users default to FALSE — they keep whatever password they
-- already have.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.must_change_password IS
    'When true, the user must change their password before any other API call succeeds. Cleared by UserDao.alterPassword once a complex enough password is set.';
