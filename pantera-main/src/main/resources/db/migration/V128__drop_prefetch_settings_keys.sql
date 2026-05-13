-- V128__drop_prefetch_settings_keys.sql
--
-- M2 (analysis/plan/v1/PLAN.md): the speculative prefetch subsystem is
-- deleted in v2.2.0. Drop any seeded `prefetch.*` rows the v2.1.x
-- SettingsBootstrap (or admin PATCH) left behind so:
--
--   1. the /api/v1/settings/runtime listing does not surface dangling keys
--      with no consumer,
--   2. operators do not see "configurable" entries that do nothing,
--   3. fresh boots and upgrades agree on the catalog
--      (SettingsKey.values() no longer enumerates prefetch.*).
--
-- This is unconditional DELETE — the keys are no longer in the catalog,
-- so any row matching prefetch.% is orphaned by definition.

DELETE FROM settings WHERE key LIKE 'prefetch.%';
