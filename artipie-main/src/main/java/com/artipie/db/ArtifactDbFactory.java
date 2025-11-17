/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.db;

import com.amihaiemil.eoyaml.YamlMapping;
import com.artipie.ArtipieException;
import com.jcabi.log.Logger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Factory to create and initialize artifacts PostgreSQL database.
 * <p/>
 * Factory accepts Artipie yaml settings file and creates database source and database structure.
 * If settings are absent in config yaml, default PostgreSQL connection parameters are used.
 * <p/>
 * Artifacts db settings section in artipie yaml:
 * <pre>{@code
 * artifacts_database:
 *   postgres_host: localhost # required, PostgreSQL host
 *   postgres_port: 5432 # optional, PostgreSQL port, default 5432
 *   postgres_database: artipie # required, PostgreSQL database name
 *   postgres_user: artipie # required, PostgreSQL username
 *   postgres_password: artipie # required, PostgreSQL password
 *   pool_max_size: 20 # optional, connection pool max size, default 20
 *   pool_min_idle: 5 # optional, connection pool min idle, default 5
 *   buffer_time_seconds: 2 # optional, buffer time in seconds, default 2
 *   buffer_size: 50 # optional, max events per batch, default 50
 *   threads_count: 3 # default 1, not required, in how many parallel threads to
 *       process artifacts data queue
 *   interval_seconds: 5 # default 1, not required, interval to check events queue and write into db
 * }</pre>
 * @since 0.31
 */
public final class ArtifactDbFactory {

    /**
     * PostgreSQL host configuration key.
     */
    public static final String YAML_HOST = "postgres_host";

    /**
     * PostgreSQL port configuration key.
     */
    public static final String YAML_PORT = "postgres_port";

    /**
     * PostgreSQL database configuration key.
     */
    public static final String YAML_DATABASE = "postgres_database";

    /**
     * PostgreSQL user configuration key.
     */
    public static final String YAML_USER = "postgres_user";

    /**
     * PostgreSQL password configuration key.
     */
    public static final String YAML_PASSWORD = "postgres_password";

    /**
     * Connection pool maximum size configuration key.
     */
    public static final String YAML_POOL_MAX_SIZE = "pool_max_size";

    /**
     * Connection pool minimum idle configuration key.
     */
    public static final String YAML_POOL_MIN_IDLE = "pool_min_idle";

    /**
     * Buffer time in seconds configuration key.
     */
    public static final String YAML_BUFFER_TIME_SECONDS = "buffer_time_seconds";

    /**
     * Buffer size (max events per batch) configuration key.
     */
    public static final String YAML_BUFFER_SIZE = "buffer_size";

    /**
     * Default PostgreSQL host.
     */
    static final String DEFAULT_HOST = "localhost";

    /**
     * Default PostgreSQL port.
     */
    static final int DEFAULT_PORT = 5432;

    /**
     * Default PostgreSQL database name.
     */
    static final String DEFAULT_DATABASE = "artifacts";

    /**
     * Default connection pool maximum size.
     * Increased from 20 to 50 to prevent thread starvation under high load.
     * Production monitoring showed thread starvation with 20 connections.
     *
     * @since 1.19.2
     */
    static final int DEFAULT_POOL_MAX_SIZE = 50;

    /**
     * Default connection pool minimum idle.
     * Increased from 5 to 10 to maintain better connection availability.
     *
     * @since 1.19.2
     */
    static final int DEFAULT_POOL_MIN_IDLE = 10;

    /**
     * Default buffer time in seconds.
     */
    static final int DEFAULT_BUFFER_TIME_SECONDS = 2;

    /**
     * Default buffer size.
     */
    static final int DEFAULT_BUFFER_SIZE = 50;

    /**
     * Settings yaml.
     */
    private final YamlMapping yaml;

    /**
     * Default database name if not specified in config.
     */
    private final String defaultDb;

    /**
     * Ctor.
     * @param yaml Settings yaml
     * @param defaultDb Default database name
     */
    public ArtifactDbFactory(final YamlMapping yaml, final String defaultDb) {
        this.yaml = yaml;
        this.defaultDb = defaultDb;
    }

    /**
     * Initialize artifacts database and mechanism to gather artifacts metadata and
     * write to db.
     * If yaml settings are absent, default PostgreSQL connection parameters are used.
     * @return DataSource with connection pooling
     * @throws ArtipieException On error
     */
    public DataSource initialize() {
        final YamlMapping config = this.yaml.yamlMapping("artifacts_database");
        
        final String host = resolveEnvVar(
            config != null && config.string(ArtifactDbFactory.YAML_HOST) != null 
                ? config.string(ArtifactDbFactory.YAML_HOST) 
                : ArtifactDbFactory.DEFAULT_HOST
        );
            
        final int port = config != null && config.string(ArtifactDbFactory.YAML_PORT) != null 
            ? Integer.parseInt(resolveEnvVar(config.string(ArtifactDbFactory.YAML_PORT)))
            : ArtifactDbFactory.DEFAULT_PORT;
            
        final String database = resolveEnvVar(
            config != null && config.string(ArtifactDbFactory.YAML_DATABASE) != null 
                ? config.string(ArtifactDbFactory.YAML_DATABASE) 
                : this.defaultDb
        );
            
        final String user = resolveEnvVar(
            config != null && config.string(ArtifactDbFactory.YAML_USER) != null 
                ? config.string(ArtifactDbFactory.YAML_USER) 
                : "artipie"
        );
            
        final String password = resolveEnvVar(
            config != null && config.string(ArtifactDbFactory.YAML_PASSWORD) != null 
                ? config.string(ArtifactDbFactory.YAML_PASSWORD) 
                : "artipie"
        );

        final int poolMaxSize = config != null && config.string(ArtifactDbFactory.YAML_POOL_MAX_SIZE) != null
            ? Integer.parseInt(config.string(ArtifactDbFactory.YAML_POOL_MAX_SIZE))
            : ArtifactDbFactory.DEFAULT_POOL_MAX_SIZE;

        final int poolMinIdle = config != null && config.string(ArtifactDbFactory.YAML_POOL_MIN_IDLE) != null
            ? Integer.parseInt(config.string(ArtifactDbFactory.YAML_POOL_MIN_IDLE))
            : ArtifactDbFactory.DEFAULT_POOL_MIN_IDLE;
        
        // Configure HikariCP connection pool for better performance and leak detection
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, database));
        hikariConfig.setUsername(user);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(poolMaxSize);
        hikariConfig.setMinimumIdle(poolMinIdle);
        hikariConfig.setConnectionTimeout(30000); // 30 seconds
        hikariConfig.setIdleTimeout(600000); // 10 minutes
        hikariConfig.setMaxLifetime(1800000); // 30 minutes
        hikariConfig.setPoolName("ArtipieDB-Pool");

        // Enable connection leak detection (60 seconds threshold)
        // Logs a warning if a connection is not returned to the pool within 60 seconds
        // Helps identify connection leaks in application code
        hikariConfig.setLeakDetectionThreshold(60000); // 60 seconds

        // Enable metrics and logging for connection pool monitoring
        hikariConfig.setRegisterMbeans(true); // Enable JMX metrics

        final HikariDataSource source = new HikariDataSource(hikariConfig);

        // Log connection pool configuration for monitoring
        Logger.info(
            ArtifactDbFactory.class,
            "HikariCP connection pool initialized: maxPoolSize=%d, minIdle=%d, leakDetectionThreshold=60s",
            poolMaxSize,
            poolMinIdle
        );
        
        ArtifactDbFactory.createStructure(source);
        return source;
    }

    /**
     * Get buffer time in seconds from configuration.
     * @return Buffer time in seconds
     */
    public int getBufferTimeSeconds() {
        final YamlMapping config = this.yaml.yamlMapping("artifacts_database");
        return config != null && config.string(ArtifactDbFactory.YAML_BUFFER_TIME_SECONDS) != null
            ? Integer.parseInt(config.string(ArtifactDbFactory.YAML_BUFFER_TIME_SECONDS))
            : ArtifactDbFactory.DEFAULT_BUFFER_TIME_SECONDS;
    }

    /**
     * Get buffer size from configuration.
     * @return Buffer size (max events per batch)
     */
    public int getBufferSize() {
        final YamlMapping config = this.yaml.yamlMapping("artifacts_database");
        return config != null && config.string(ArtifactDbFactory.YAML_BUFFER_SIZE) != null
            ? Integer.parseInt(config.string(ArtifactDbFactory.YAML_BUFFER_SIZE))
            : ArtifactDbFactory.DEFAULT_BUFFER_SIZE;
    }

    /**
     * Resolve environment variable placeholders in the format ${VAR_NAME}.
     * @param value Value that may contain environment variable placeholders
     * @return Resolved value with environment variables substituted
     */
    private static String resolveEnvVar(final String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        // Match ${VAR_NAME} pattern
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String envValue = System.getenv(envVar);
            if (envValue != null) {
                result = result.replace("${" + envVar + "}", envValue);
            }
        }
        return result;
    }

    /**
     * Create db structure to write artifacts data.
     * @param source Database source
     * @throws ArtipieException On error
     */
    private static void createStructure(final DataSource source) {
        try (Connection conn = source.getConnection();
            Statement statement = conn.createStatement()) {
            statement.executeUpdate(
                String.join(
                    "\n",
                    "CREATE TABLE IF NOT EXISTS artifacts(",
                    "   id BIGSERIAL PRIMARY KEY,",
                    "   repo_type VARCHAR NOT NULL,",
                    "   repo_name VARCHAR NOT NULL,",
                    "   name VARCHAR NOT NULL,",
                    "   version VARCHAR NOT NULL,",
                    "   size BIGINT NOT NULL,",
                    "   created_date BIGINT NOT NULL,",
                    "   release_date BIGINT,",
                    "   owner VARCHAR NOT NULL,",
                    "   UNIQUE (repo_name, name, version) ",
                    ");"
                )
            );
            // Backward compatibility: add release_date if table already existed
            statement.executeUpdate(
                "ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS release_date BIGINT"
            );
            
            // Performance indexes for artifacts table
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_repo_lookup ON artifacts(repo_name, name, version)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_repo_type_name ON artifacts(repo_type, repo_name, name)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_created_date ON artifacts(created_date)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_owner ON artifacts(owner)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_release_date ON artifacts(release_date) WHERE release_date IS NOT NULL"
            );
            statement.executeUpdate(
                String.join(
                    "\n",
                    "CREATE TABLE IF NOT EXISTS artifact_cooldowns(",
                    "   id BIGSERIAL PRIMARY KEY,",
                    "   repo_type VARCHAR NOT NULL,",
                    "   repo_name VARCHAR NOT NULL,",
                    "   artifact VARCHAR NOT NULL,",
                    "   version VARCHAR NOT NULL,",
                    "   reason VARCHAR NOT NULL,",
                    "   status VARCHAR NOT NULL,",
                    "   blocked_by VARCHAR NOT NULL,",
                    "   blocked_at BIGINT NOT NULL,",
                    "   blocked_until BIGINT NOT NULL,",
                    "   unblocked_at BIGINT,",
                    "   unblocked_by VARCHAR,",
                    "   parent_block_id BIGINT,",
                    "   CONSTRAINT cooldown_parent_fk FOREIGN KEY (parent_block_id)",
                    "       REFERENCES artifact_cooldowns(id) ON DELETE CASCADE,",
                    "   CONSTRAINT cooldown_artifact_unique UNIQUE (repo_name, artifact, version)",
                    ");"
                )
            );
            statement.executeUpdate(
                String.join(
                    "\n",
                    "CREATE TABLE IF NOT EXISTS artifact_cooldown_attempts(",
                    "   id BIGSERIAL PRIMARY KEY,",
                    "   block_id BIGINT NOT NULL REFERENCES artifact_cooldowns(id) ON DELETE CASCADE,",
                    "   requested_by VARCHAR NOT NULL,",
                    "   attempted_at BIGINT NOT NULL",
                    ");"
                )
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_cooldowns_repo_artifact ON artifact_cooldowns(repo_name, artifact, version)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_cooldowns_status ON artifact_cooldowns(status)"
            );
            statement.executeUpdate(
                "UPDATE artifact_cooldowns SET status = 'INACTIVE' WHERE status = 'MANUAL'"
            );
            statement.executeUpdate(
                String.join(
                    "\n",
                    "CREATE TABLE IF NOT EXISTS import_sessions(",
                    "   id BIGSERIAL PRIMARY KEY,",
                    "   idempotency_key VARCHAR(1000) NOT NULL UNIQUE,",
                    "   repo_name VARCHAR NOT NULL,",
                    "   repo_type VARCHAR NOT NULL,",
                    "   artifact_path TEXT NOT NULL,",
                    "   artifact_name VARCHAR,",
                    "   artifact_version VARCHAR,",
                    "   size_bytes BIGINT,",
                    "   checksum_sha1 VARCHAR(128),",
                    "   checksum_sha256 VARCHAR(128),",
                    "   checksum_md5 VARCHAR(128),",
                    "   checksum_policy VARCHAR(16) NOT NULL,",
                    "   status VARCHAR(32) NOT NULL,",
                    "   attempt_count INTEGER NOT NULL DEFAULT 1,",
                    "   created_at TIMESTAMP NOT NULL,",
                    "   updated_at TIMESTAMP NOT NULL,",
                    "   completed_at TIMESTAMP,",
                    "   last_error TEXT,",
                    "   quarantine_path TEXT",
                    ");"
                )
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_import_sessions_repo ON import_sessions(repo_name)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_import_sessions_status ON import_sessions(status)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_import_sessions_repo_path ON import_sessions(repo_name, artifact_path)"
            );
        } catch (final SQLException error) {
            throw new ArtipieException(error);
        }
    }
}
