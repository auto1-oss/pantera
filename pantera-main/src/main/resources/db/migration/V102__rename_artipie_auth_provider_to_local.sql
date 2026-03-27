-- Rename the built-in auth provider type from 'artipie' to 'local'.
-- Update existing rows and change the column default.

UPDATE users SET auth_provider = 'local' WHERE auth_provider = 'artipie';

ALTER TABLE users ALTER COLUMN auth_provider SET DEFAULT 'local';
