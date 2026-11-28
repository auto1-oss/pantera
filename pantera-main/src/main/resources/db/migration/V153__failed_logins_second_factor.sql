-- V153__failed_logins_second_factor.sql
-- The failed-logins policy (card "Failed logins" in System Settings) also
-- counts sign-ins whose password was accepted but whose second factor was
-- not confirmed: a push denied or not answered, a wrong code. Such a
-- sign-in is never a wrong password: it neither delays nor locks. After
-- the limit, password sign-in of the user is paused, so that no further
-- push reaches the user's phone. This replaces the sign-in form's own
-- limit ("Login Throttling", keys login_throttle_*), which was kept in the
-- memory of each node and only covered the form.
--
--   failed_logins_second_factor_limit         = 5   (pause after 5 unconfirmed second factors; 0 = no pause)
--   failed_logins_second_factor_pause_minutes = 15  (length of the pause; unconfirmed second factors
--                                                    count while each comes within this time of the previous one)
--
-- The loader (FailedLoginsSettingsLoader) falls back to these defaults
-- when a row is absent.
ALTER TABLE login_failures
    ADD COLUMN IF NOT EXISTS second_factor_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_second_factor_at TIMESTAMPTZ;

INSERT INTO auth_settings (key, value) VALUES
    ('failed_logins_second_factor_limit', '5'),
    ('failed_logins_second_factor_pause_minutes', '15')
ON CONFLICT (key) DO NOTHING;

-- The sign-in failures report gets the kind
--   second_factor = sign-ins whose second factor was not confirmed (hits)
--                   and password sign-ins refused during the pause (refused),
--                   with the count toward the pause (failures) and the end of
--                   the pause (blocked_until, null before the limit)
-- in place of form_limit. The sign-in form's own limit is gone: its
-- settings and its lines of the report go with it.
DELETE FROM auth_settings
    WHERE key IN ('login_throttle_max_failures', 'login_throttle_window_seconds');
DELETE FROM sign_in_failures WHERE kind = 'form_limit';
