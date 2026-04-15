-- Raise default api_token_max_ttl_seconds from 90 days (7,776,000)
-- to 365 days (31,536,000) so the UI token-generation dropdown can
-- display the full 30 / 90 / 180 / 365 day range.
--
-- Operators that previously set a custom value are preserved: the
-- UPDATE only fires when the row still holds the V107 default value.
-- Fresh v2.1.3+ installs run V107 (seeds 90d) then immediately V119
-- (raises to 365d) in the same migration pass, so new deployments
-- also get 365d without needing manual admin action.
UPDATE auth_settings
SET value = '31536000',
    updated_at = NOW()
WHERE key = 'api_token_max_ttl_seconds'
  AND value = '7776000';
