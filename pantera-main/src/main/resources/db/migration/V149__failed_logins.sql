-- V149__failed_logins.sql
-- Failed password logins per user name: the count behind the login delay
-- and the account lock (card "Failed logins" in System Settings).
--
-- One row per user name that has failed logins. The name is kept in lower
-- case and need not belong to a Pantera user: a login that is checked with
-- an identity provider counts for the directory account behind it. A row
-- goes away with a successful password login, an unlock, or an admin's
-- "Unlock all".

CREATE TABLE IF NOT EXISTS login_failures (
    username        VARCHAR(255) PRIMARY KEY,
    failures        INTEGER      NOT NULL DEFAULT 0,
    last_failure_at TIMESTAMPTZ,
    locked_at       TIMESTAMPTZ,
    client_ip       VARCHAR(64)  NOT NULL DEFAULT '',
    user_agent      VARCHAR(512) NOT NULL DEFAULT '',
    sign_in_path    VARCHAR(16)  NOT NULL DEFAULT '',
    credential      VARCHAR(16)  NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_login_failures_locked
    ON login_failures (locked_at) WHERE locked_at IS NOT NULL;

-- The policy. Matches FailedLoginsConfig.defaults():
--   failed_logins_delay_from_failure   = 2     (logins are delayed from the 2nd failure; 0 = no delay)
--   failed_logins_first_delay_seconds  = 1     (delay = this x failures squared)
--   failed_logins_max_delay_seconds    = 60    (longest delay)
--   failed_logins_lock_after           = 3     (lock after 3 failures; 0 = no lock)
--   failed_logins_unlock_after_minutes = 0     (0 = only an admin unlocks)
--   failed_logins_forget_after_minutes = 0     (0 = failures are never forgotten by time)
--   failed_logins_count_rejected_keys  = false (a rejected API key is not a failed login)
--   failed_logins_lock_all_credentials = true  (a lock blocks API keys, sessions and the provider button too;
--                                               false = password sign-in only)
-- The loader (FailedLoginsSettingsLoader) falls back to these defaults
-- when a row is absent.

INSERT INTO auth_settings (key, value) VALUES
    ('failed_logins_delay_from_failure', '2'),
    ('failed_logins_first_delay_seconds', '1'),
    ('failed_logins_max_delay_seconds', '60'),
    ('failed_logins_lock_after', '3'),
    ('failed_logins_unlock_after_minutes', '0'),
    ('failed_logins_forget_after_minutes', '0'),
    ('failed_logins_count_rejected_keys', 'false'),
    ('failed_logins_lock_all_credentials', 'true')
ON CONFLICT (key) DO NOTHING;
