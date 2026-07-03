-- V122__circuit_breaker_settings.sql
-- Seed rate-over-sliding-window circuit breaker settings into the
-- existing auth_settings key/value table. The table is semantically
-- a general "admin-tunable system settings" store even though its
-- name carries "auth" for historical reasons — V107 was the first
-- user of it.
--
-- Matches AutoBlockSettings.defaults() in pantera-core:
--   failureRateThreshold      = 0.5   (50 % errors trip)
--   minimumNumberOfCalls      = 20    (gate against cold-start bursts)
--   slidingWindowSeconds      = 30    (rolling window length)
--   initialBlockDurationSec   = 20    (first block after trip)
--   maxBlockDurationSeconds   = 300   (cap after repeated trips)
--
-- These rows make the defaults explicit + queryable. The admin settings
-- UI reads / writes the same keys; the in-memory loader falls back to
-- AutoBlockSettings.defaults() if rows are absent, so forgetting to
-- deploy this migration does not break the runtime — just removes the
-- admin UI's ability to show/edit the values.

INSERT INTO auth_settings (key, value) VALUES
    ('circuit_breaker_failure_rate_threshold', '0.5'),
    ('circuit_breaker_minimum_number_of_calls', '20'),
    ('circuit_breaker_sliding_window_seconds', '30'),
    ('circuit_breaker_initial_block_seconds', '20'),
    ('circuit_breaker_max_block_seconds', '300')
ON CONFLICT (key) DO NOTHING;

COMMENT ON TABLE auth_settings IS 'UI-configurable admin policy settings — auth, circuit breaker, etc.';
