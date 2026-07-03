-- V132__bulkhead_settings.sql
-- Seed adaptive bulkhead tunables into the runtime settings table.
--
-- Pantera's per-repo bulkhead now ships with an AIMD controller that grows
-- the in-flight permit ceiling on healthy windows (no errors, peak latency
-- under target) and halves it on a bad window. These rows make the defaults
-- explicit + editable from the admin settings UI. The in-memory
-- BulkheadTuning.fromMap() falls back to its hard-coded defaults if a row
-- is absent, so forgetting to deploy this migration only removes the
-- ability to edit the values — it does not break the runtime.
--
-- Matches BulkheadTuning.defaults() in pantera-main:
--   adaptive          = true
--   min_permits       = 5
--   max_permits       = 100
--   initial_permits   = 10
--   target_p99_ms     = 500
--   window_seconds    = 5
--   ramp_up_step      = 1
--   ramp_down_factor  = 0.5

INSERT INTO settings (key, value, updated_by) VALUES
    ('http_client.bulkhead.adaptive',         '{"value": true}'::jsonb, 'migration:V132'),
    ('http_client.bulkhead.min_permits',      '{"value": 5}'::jsonb,    'migration:V132'),
    ('http_client.bulkhead.max_permits',      '{"value": 100}'::jsonb,  'migration:V132'),
    ('http_client.bulkhead.initial_permits',  '{"value": 10}'::jsonb,   'migration:V132'),
    ('http_client.bulkhead.target_p99_ms',    '{"value": 500}'::jsonb,  'migration:V132'),
    ('http_client.bulkhead.window_seconds',   '{"value": 5}'::jsonb,    'migration:V132'),
    ('http_client.bulkhead.ramp_up_step',     '{"value": 1}'::jsonb,    'migration:V132'),
    ('http_client.bulkhead.ramp_down_factor', '{"value": 0.5}'::jsonb,  'migration:V132')
ON CONFLICT (key) DO NOTHING;
