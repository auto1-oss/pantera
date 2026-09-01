-- 2.2.9 (SecOps sso-oidc, identity-confusion): bind SSO logins to a stable
-- external identity (provider|issuer|subject) instead of the mutable IdP
-- username, so a colliding preferred_username can never inherit an
-- existing account's roles. NULL for local (password) accounts and for
-- legacy SSO users until their first post-upgrade login binds them.
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_subject VARCHAR(512);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_sso_subject
    ON users (sso_subject) WHERE sso_subject IS NOT NULL;
