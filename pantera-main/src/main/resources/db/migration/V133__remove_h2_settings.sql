-- V133__remove_h2_settings.sql
--
-- v2.2.0 (final): outbound HTTP/2 is removed. The perf-pack briefly defaulted
-- the upstream client to HTTP/2, but Jetty issue #12776 corrupts the shared
-- ByteBufferPool whenever any in-flight H2 stream is cancelled — sibling
-- streams on the same connection then fail with `EOFException: Stream has
-- been reset`. proxy.golang.org, Maven Central, npmjs and PyPI all accept
-- HTTP/1.1; Nexus and JFrog Artifactory use HTTP/1.1 to upstream with a
-- keep-alive pool. Pantera 2.2.0 final matches that pattern.
--
-- The runtime catalog no longer enumerates `http_client.protocol`,
-- `http_client.http2_max_pool_size`, or `http_client.http2_multiplexing_limit`
-- (see SettingsKey). Drop any rows the v2.2.0 perf-pack SettingsBootstrap (or
-- admin PATCH) seeded for these keys so:
--
--   1. /api/v1/settings/runtime does not surface dangling keys with no consumer,
--   2. operators do not see "configurable" entries that do nothing,
--   3. fresh boots and upgrades agree on the catalog.
--
-- Idempotent unconditional DELETE — the keys are no longer in the catalog,
-- so any row matching them is orphaned by definition.

DELETE FROM settings WHERE key IN (
    'http_client.protocol',
    'http_client.http2_max_pool_size',
    'http_client.http2_multiplexing_limit'
);
