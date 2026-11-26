-- Where an identity provider takes passwords, per provider type
-- (System Settings, "Password sign-in" on the provider cards).
--
-- A provider such as Okta or Keycloak checks a user name and password
-- against the directory. The mode says how far that check reaches:
--   all  = the sign-in form and repository requests (the default: what
--          every version before 2.2.15 did for a configured provider)
--   form = the sign-in form and POST /api/v1/auth/token only; a
--          repository request with a directory password is refused
--          without asking the provider
--   off  = nowhere; the provider checks no password
-- The sign-in button of a provider (browser redirect) is not affected.
--
-- The loader (PasswordSignInSettingsLoader) falls back to
-- PANTERA_PASSWORD_SIGN_IN_<TYPE> and then to "all" when a row is absent.

INSERT INTO auth_settings (key, value) VALUES
    ('password_sign_in_okta', 'all'),
    ('password_sign_in_keycloak', 'all'),
    ('password_sign_in_github', 'all')
ON CONFLICT (key) DO NOTHING;
