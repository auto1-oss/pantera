-- V118: seed the two mandatory auth provider types that must always be
-- present in the `auth_providers` table.
--
-- The CHANGELOG for 2.1.0 states that the `local` and `jwt-password`
-- provider types "are mandatory and cannot be removed". DbGatedAuth
-- gates every dynamic provider on the row's `enabled` flag — if the
-- row doesn't exist at all, the provider silently returns empty from
-- every authentication attempt. Existing deployments upgraded from
-- pre-2.1.0 never had these rows written, so their jwt-password
-- (API-token-as-password) flow was broken: the factory constructed
-- the provider, wrapped it in DbGatedAuth, and the cache lookup then
-- returned false on every request.
--
-- INSERT … ON CONFLICT (type) DO NOTHING is idempotent: fresh installs
-- get both rows; deployments that already have them are unaffected;
-- deployments that explicitly disabled one via the UI keep their
-- choice because the conflict clause skips the UPDATE entirely.
--
-- Priorities follow the normal chain order (local first, jwt-password
-- second). SSO providers that operators add via the UI typically
-- receive priority 10+ so they're evaluated ahead of these fallbacks.

INSERT INTO auth_providers (type, priority, enabled, config)
VALUES
    ('local',        0, TRUE, '{}'::jsonb),
    ('jwt-password', 1, TRUE, '{}'::jsonb)
ON CONFLICT (type) DO NOTHING;
