-- V138__unshadow_upstream_breaker_settings.sql
-- V136 unconditionally seeded the upstream_breaker_* keys into
-- auth_settings, so UpstreamBreakerSettingsLoader's documented precedence
-- (DB row -> env var PANTERA_UPSTREAM_BREAKER_* -> hardcoded default) never
-- reaches the env tier: a DB row is always present after V136 ran, so an
-- operator's PANTERA_UPSTREAM_BREAKER_* env vars are silently shadowed from
-- the moment they upgrade. This is the identical bug fixed for V137
-- (client_base_url settings) by simply not seeding at all -- see that
-- migration's comment for the full precedence rationale.
--
-- V136 has ALREADY RUN against deployed databases. Flyway checksums every
-- applied migration's content, so V136 cannot be edited in place -- doing
-- so would fail every existing deployment at startup with a checksum
-- mismatch. This migration instead deletes the rows V136 wrote, as a
-- follow-up correction.
--
-- The delete is scoped to rows whose value is STILL EXACTLY the value V136
-- seeded (matching CircuitBreakerConfig.defaults(), the same literals
-- documented in V136's header comment). An admin who has since written a
-- different value via PUT /api/v1/admin/upstream-breaker-settings -- the
-- loader's DB tier correctly winning by design -- keeps that row untouched:
-- deleting an intentionally-set value would be data loss the admin never
-- asked for. A row that still holds the untouched V136 default is
-- indistinguishable from "never customised", so removing it is safe and
-- restores the env -> hardcoded-default fallback for that key.
--
-- After this migration:
--   * A row unchanged since V136 (value == the V136 default) is removed, so
--     the env tier becomes reachable again for that key.
--   * A row an admin has explicitly customised (value != the V136 default)
--     is left exactly as-is -- that is a deliberate override, not migration
--     residue, and the DB tier keeps winning as documented.
DELETE FROM auth_settings
WHERE (key, value) IN (
    ('upstream_breaker_failure_rate_threshold', '0.5'),
    ('upstream_breaker_minimum_calls', '10'),
    ('upstream_breaker_window_seconds', '30'),
    ('upstream_breaker_seed_backoff_seconds', '2'),
    ('upstream_breaker_max_backoff_seconds', '3600')
);
