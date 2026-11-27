-- V152__docker_tag_refresh.sql
-- How long a docker-proxy repository serves a cached image TAG (e.g.
-- `latest`, `20`) before it checks the tag against the upstream again
-- (System Settings, card "Docker Proxy Tags").
--
--   docker_tag_refresh_seconds = 3600  (one hour)
--
-- Integer seconds, 0..31536000 (one year). 0 checks the tag on every
-- request. When a cached tag is older than this on a node, Pantera asks the
-- upstream for the tag's digest with a HEAD: the same digest keeps the
-- cached manifest, a new digest fetches and stores the new manifest. A
-- digest reference is never checked again. A repository overrides the
-- value with its own `tag_refresh_seconds`.
--
-- The loader (DockerTagRefreshSettingsLoader) falls back to
-- PANTERA_DOCKER_TAG_REFRESH_SECONDS and then to 3600 when the row is
-- absent.

INSERT INTO auth_settings (key, value) VALUES
    ('docker_tag_refresh_seconds', '3600')
ON CONFLICT (key) DO NOTHING;
