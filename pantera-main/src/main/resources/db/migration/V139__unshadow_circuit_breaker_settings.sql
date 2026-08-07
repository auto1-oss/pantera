-- V139__unshadow_circuit_breaker_settings.sql
-- V122 unconditionally seeded the circuit_breaker_* keys into auth_settings,
-- so CircuitBreakerSettingsLoader's documented precedence (DB row -> env var
-- PANTERA_CIRCUIT_BREAKER_* -> hardcoded default) never reaches the env
-- tier: a DB row is always present after V122 ran, so an operator's
-- PANTERA_CIRCUIT_BREAKER_* env vars are silently shadowed from the moment
-- they upgrade. This is the identical bug fixed for V136 (upstream_breaker_*
-- settings) by V138 -- see that migration's comment for the full precedence
-- rationale. It is DISTINCT from that fix: circuit_breaker_* configures the
-- GROUP-MEMBER breaker (AutoBlockSettings / AutoBlockRegistry / MemberSlice),
-- not the outbound HTTP breaker upstream_breaker_* governs.
--
-- V122 has ALREADY RUN against deployed databases. Flyway checksums every
-- applied migration's content, so V122 cannot be edited in place -- doing
-- so would fail every existing deployment at startup with a checksum
-- mismatch. This migration instead deletes the rows V122 wrote, as a
-- follow-up correction.
--
-- The delete is scoped to rows whose value is STILL EXACTLY the value V122
-- seeded (matching AutoBlockSettings.defaults(), the same literals
-- documented in V122's header comment). An admin who has since written a
-- different value via PUT /api/v1/admin/circuit-breaker-settings -- the
-- loader's DB tier correctly winning by design -- keeps that row untouched:
-- deleting an intentionally-set value would be data loss the admin never
-- asked for. A row that still holds the untouched V122 default is
-- indistinguishable from "never customised", so removing it is safe and
-- restores the env -> hardcoded-default fallback for that key.
--
-- After this migration:
--   * A row unchanged since V122 (value == the V122 default) is removed, so
--     the env tier becomes reachable again for that key.
--   * A row an admin has explicitly customised (value != the V122 default)
--     is left exactly as-is -- that is a deliberate override, not migration
--     residue, and the DB tier keeps winning as documented.
DELETE FROM auth_settings
WHERE (key, value) IN (
    ('circuit_breaker_failure_rate_threshold', '0.5'),
    ('circuit_breaker_minimum_number_of_calls', '20'),
    ('circuit_breaker_sliding_window_seconds', '30'),
    ('circuit_breaker_initial_block_seconds', '20'),
    ('circuit_breaker_max_block_seconds', '300')
);
