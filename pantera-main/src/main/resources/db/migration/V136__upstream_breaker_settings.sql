-- V136__upstream_breaker_settings.sql
-- Seed the OUTBOUND HTTP-client circuit-breaker settings into the
-- auth_settings key/value store. These govern the per-upstream
-- (scheme://host:port) breaker in JettyClientSlices and are DISTINCT
-- from the circuit_breaker_* keys (V122), which govern the group-member
-- breaker (AutoBlockSettings, per member repository).
--
-- Matches CircuitBreakerConfig.defaults() in http-client:
--   failureRateThreshold   = 0.5   (50 % of window calls must fail)
--   minimumCalls           = 10    (rate not evaluated below this volume)
--   windowSeconds          = 30    (per-second sliding window length)
--   seedBackoffSeconds     = 2     (first block after a trip; Fibonacci growth)
--   maxBackoffSeconds      = 3600  (block cap after repeated failed probes)
--
-- The in-memory loader (UpstreamBreakerSettingsLoader) falls back to the
-- hardcoded defaults when rows are absent, so this migration is about
-- making the values explicit and admin-editable, not about correctness.

INSERT INTO auth_settings (key, value) VALUES
    ('upstream_breaker_failure_rate_threshold', '0.5'),
    ('upstream_breaker_minimum_calls', '10'),
    ('upstream_breaker_window_seconds', '30'),
    ('upstream_breaker_seed_backoff_seconds', '2'),
    ('upstream_breaker_max_backoff_seconds', '3600')
ON CONFLICT (key) DO NOTHING;
