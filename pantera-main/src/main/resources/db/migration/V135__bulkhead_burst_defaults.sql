-- V135__bulkhead_burst_defaults.sql
--
-- Raise the seeded bulkhead defaults from the original warm-cache values
-- (initial_permits=10, ramp_up_step=1) to cold-burst-friendly values
-- (initial_permits=40, ramp_up_step=4). V132 seeded the row values with
-- ON CONFLICT DO NOTHING; fresh installs would now pick up the new
-- defaults via BulkheadTuning.defaults(), but already-deployed clusters
-- still hold the V132 row values and need an explicit UPDATE.
--
-- Phase-timer diagnostics on a 1,557-coord cold maven_group resolve
-- (2026-06-30) showed the adaptive bulkhead climbed only 10 -> 24
-- permits over the 65 s bench window, capping throughput at ~20 RPS
-- vs direct Maven Central's ~34 RPS — ~40 % throughput cap and ~20 s
-- wall-time penalty. The new initial_permits=40 absorbs the cold-burst
-- fan-out without throttling; ramp_up_step=4 reaches max_permits=100
-- inside the first 75 s of a sustained burst (vs 450 s previously).
--
-- AIMD safety properties unchanged: max_permits=100 cap, rampDownFactor=
-- 0.5 still halves permits on any bad window, min_permits=5 still
-- preserves the lower-bound floor.
--
-- Only updates rows whose values are still the V132 seed values; manual
-- admin overrides (e.g. set via the System Settings UI) are preserved.

UPDATE settings
   SET value      = '{"value": 40}'::jsonb,
       updated_by = 'migration:V135'
 WHERE key = 'http_client.bulkhead.initial_permits'
   AND value = '{"value": 10}'::jsonb;

UPDATE settings
   SET value      = '{"value": 4}'::jsonb,
       updated_by = 'migration:V135'
 WHERE key = 'http_client.bulkhead.ramp_up_step'
   AND value = '{"value": 1}'::jsonb;
