-- Create separate databases for old and new Pantera instances
CREATE DATABASE artifacts_old;
CREATE DATABASE artifacts_new;

-- Grant access
GRANT ALL PRIVILEGES ON DATABASE artifacts_old TO pantera;
GRANT ALL PRIVILEGES ON DATABASE artifacts_new TO pantera;
