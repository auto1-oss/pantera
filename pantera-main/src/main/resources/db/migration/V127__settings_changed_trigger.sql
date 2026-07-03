-- V127__settings_changed_trigger.sql
-- Emit a NOTIFY on the 'settings_changed' channel whenever a settings row
-- is inserted, updated, or deleted. Payload is the affected key.

CREATE OR REPLACE FUNCTION notify_settings_changed() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('settings_changed', COALESCE(NEW.key, OLD.key));
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS settings_change_notify ON settings;
CREATE TRIGGER settings_change_notify
    AFTER INSERT OR UPDATE OR DELETE ON settings
    FOR EACH ROW EXECUTE FUNCTION notify_settings_changed();
