-- V126__cooldown_release_date.sql
-- Add release_date column to cooldown tables so the UI can display
-- when the artifact was actually published (not just when it was blocked).

ALTER TABLE artifact_cooldowns
    ADD COLUMN IF NOT EXISTS release_date BIGINT;

ALTER TABLE artifact_cooldowns_history
    ADD COLUMN IF NOT EXISTS release_date BIGINT;
