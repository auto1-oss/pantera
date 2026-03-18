-- Create separate databases for old and new Artipie instances
CREATE DATABASE artifacts_old;
CREATE DATABASE artifacts_new;

-- Grant access
GRANT ALL PRIVILEGES ON DATABASE artifacts_old TO artipie;
GRANT ALL PRIVILEGES ON DATABASE artifacts_new TO artipie;
