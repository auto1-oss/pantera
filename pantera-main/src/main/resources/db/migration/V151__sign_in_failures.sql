-- The sign-in failures report: who fails to sign in, with what, and from
-- where (System -> Sign-in failures).
--
-- One line per kind, user name, client address and API key. A node notes
-- the events in memory and adds them to their lines once a minute, so a
-- client that repeats a failing request writes one row a minute, not one
-- per request. Lines are removed when their last event is older than the
-- retention time.
--
-- kind:
--   api_key    = a rejected API key (reason api_key_expired, api_key_revoked,
--                api_key_refused, or api_key_unknown for a key this
--                installation did not sign: then verified = false and the
--                user name is what the client sent, which proves nothing)
--   password   = a failed password login (hits) and logins refused during a
--                delay or a lock without a check (refused)
--   form_limit = a login refused by the sign-in form's own limit, with the
--                failures counted in its window and the end of the block
--   unlock     = an unlock by an administrator (actor), kept so the report
--                can say who unlocked a user
CREATE TABLE IF NOT EXISTS sign_in_failures (
    id            BIGSERIAL PRIMARY KEY,
    kind          VARCHAR(16)  NOT NULL,
    username      VARCHAR(255) NOT NULL DEFAULT '',
    client_ip     VARCHAR(64)  NOT NULL DEFAULT '',
    token_id      VARCHAR(64)  NOT NULL DEFAULT '',
    reason        VARCHAR(64)  NOT NULL DEFAULT '',
    verified      BOOLEAN      NOT NULL DEFAULT TRUE,
    user_agent    VARCHAR(512) NOT NULL DEFAULT '',
    sign_in_path  VARCHAR(64)  NOT NULL DEFAULT '',
    hits          BIGINT       NOT NULL DEFAULT 0,
    refused       BIGINT       NOT NULL DEFAULT 0,
    first_seen    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    failures      INTEGER      NOT NULL DEFAULT 0,
    blocked_until TIMESTAMPTZ,
    actor         VARCHAR(255) NOT NULL DEFAULT '',
    CONSTRAINT sign_in_failures_line UNIQUE (kind, username, client_ip, token_id)
);

-- The lists read one kind, most recent first; the purge reads by time.
CREATE INDEX IF NOT EXISTS idx_sign_in_failures_kind_seen
    ON sign_in_failures (kind, last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_sign_in_failures_seen
    ON sign_in_failures (last_seen);

-- The policy. Matches SignInReportConfig.defaults():
--   sign_in_report_enabled        = true (false: nothing is noted or stored)
--   sign_in_report_retention_days = 90   (1-3650: a line is removed this many
--                                         days after its last event)
-- The loader (SignInReportSettingsLoader) falls back to
-- PANTERA_SIGN_IN_REPORT_* and then to these defaults when a row is absent.
INSERT INTO auth_settings (key, value) VALUES
    ('sign_in_report_enabled', 'true'),
    ('sign_in_report_retention_days', '90')
ON CONFLICT (key) DO NOTHING;
