ALTER TABLE user_tokens ADD COLUMN IF NOT EXISTS token_type VARCHAR(10) NOT NULL DEFAULT 'api';

COMMENT ON COLUMN user_tokens.token_type IS 'Token type: api or refresh';
