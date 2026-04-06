/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.db;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Factory to create and initialize artifacts PostgreSQL database.
 * <p/>
 * Factory accepts Pantera yaml settings file and creates database source and database structure.
 * If settings are absent in config yaml, default PostgreSQL connection parameters are used.
 * <p/>
 * Artifacts db settings section in pantera yaml:
 * <pre>{@code
 * artifacts_database:
 *   postgres_host: localhost # required, PostgreSQL host
 *   postgres_port: 5432 # optional, PostgreSQL port, default 5432
 *   postgres_database: pantera # required, PostgreSQL database name
 *   postgres_user: pantera # required, PostgreSQL username
 *   postgres_password: pantera # required, PostgreSQL password
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
    static final int DEFAULT_POOL_MAX_SIZE =
        ConfigDefaults.getInt("PANTERA_DB_POOL_MAX", 50);

    /**
     * Default connection pool minimum idle.
     * Increased from 5 to 10 to maintain better connection availability.
     *
     * @since 1.19.2
     */
    static final int DEFAULT_POOL_MIN_IDLE =
        ConfigDefaults.getInt("PANTERA_DB_POOL_MIN", 10);

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
     * Create a dedicated write pool for DbConsumer batch inserts.
     *
     * <p>Smaller pool (max=10, min=2) prevents DbConsumer write batches from
     * starving API and dashboard queries on the main pool.
     *
     * @return DataSource sized for write-only workloads
     */
    public DataSource initializeWritePool() {
        return this.initializeWithOverrides(
            "PanteraWrite-Pool",
            ConfigDefaults.getInt("PANTERA_DB_WRITE_POOL_MAX", 10),
            ConfigDefaults.getInt("PANTERA_DB_WRITE_POOL_MIN", 2)
        );
    }

    /**
     * Initialize artifacts database and mechanism to gather artifacts metadata and
     * write to db.
     * If yaml settings are absent, default PostgreSQL connection parameters are used.
     * @return DataSource with connection pooling
     * @throws PanteraException On error
     */
    public DataSource initialize() {
        final YamlMapping config = this.yaml.yamlMapping("artifacts_database");
        final int maxSize = config != null && config.string(ArtifactDbFactory.YAML_POOL_MAX_SIZE) != null
            ? Integer.parseInt(config.string(ArtifactDbFactory.YAML_POOL_MAX_SIZE))
            : ArtifactDbFactory.DEFAULT_POOL_MAX_SIZE;
        final int minIdle = config != null && config.string(ArtifactDbFactory.YAML_POOL_MIN_IDLE) != null
            ? Integer.parseInt(config.string(ArtifactDbFactory.YAML_POOL_MIN_IDLE))
            : ArtifactDbFactory.DEFAULT_POOL_MIN_IDLE;
        final DataSource source = this.initializeWithOverrides("PanteraDB-Pool", maxSize, minIdle);
        ArtifactDbFactory.createStructure(source);
        return source;
    }

    /**
     * Internal pool creation with name and size overrides.
     * @param poolName HikariCP pool name for JMX and metrics
     * @param maxSize Maximum pool size
     * @param minIdle Minimum idle connections
     * @return Configured HikariDataSource
     */
    private DataSource initializeWithOverrides(
        final String poolName, final int maxSize, final int minIdle) {
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
                : "pantera"
        );
        final String password = resolveEnvVar(
            config != null && config.string(ArtifactDbFactory.YAML_PASSWORD) != null
                ? config.string(ArtifactDbFactory.YAML_PASSWORD)
                : "pantera"
        );

        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, database));
        hikariConfig.setUsername(user);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(maxSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setConnectionTimeout(
            ConfigDefaults.getLong("PANTERA_DB_CONNECTION_TIMEOUT_MS", 5000L)
        );
        hikariConfig.setIdleTimeout(
            ConfigDefaults.getLong("PANTERA_DB_IDLE_TIMEOUT_MS", 600_000L)
        );
        hikariConfig.setMaxLifetime(
            ConfigDefaults.getLong("PANTERA_DB_MAX_LIFETIME_MS", 1_800_000L)
        );
        hikariConfig.setPoolName(poolName);
        hikariConfig.setLeakDetectionThreshold(
            ConfigDefaults.getLong("PANTERA_DB_LEAK_DETECTION_MS", 300000)
        );
        hikariConfig.setRegisterMbeans(true);

        final HikariDataSource source = new HikariDataSource(hikariConfig);
        EcsLogger.info("com.auto1.pantera.db")
            .message("HikariCP pool initialized: " + poolName
                + " (max=" + maxSize + ", min=" + minIdle + ")")
            .eventCategory("database")
            .eventAction("connection_pool_init")
            .eventOutcome("success")
            .log();
        return source;
    }

    /**
     * Register HikariCP metrics with Micrometer/Prometheus.
     * Must be called after Vert.x is initialized so BackendRegistries has a registry.
     * Exposes: hikaricp_connections_active, hikaricp_connections_idle,
     * hikaricp_connections_pending, hikaricp_connections_timeout_total, etc.
     * @param dataSource HikariCP data source
     * @param registry Micrometer meter registry
     */
    public static void enableMetrics(
        final javax.sql.DataSource dataSource,
        final io.micrometer.core.instrument.MeterRegistry registry
    ) {
        if (registry == null) {
            return;
        }
        if (dataSource instanceof HikariDataSource hds) {
            hds.setMetricsTrackerFactory(
                new com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory(registry)
            );
            EcsLogger.info("com.auto1.pantera.db")
                .message("HikariCP Micrometer metrics enabled for pool: " + hds.getPoolName())
                .eventCategory("database")
                .eventAction("metrics_init")
                .log();
        }
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
     * @throws PanteraException On error
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
            // Migration: Add path_prefix column for path-based group index lookup
            statement.executeUpdate(
                "ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS path_prefix VARCHAR"
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
            // Covering index for locate() — enables index-only scan
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_locate ON artifacts (name, repo_name) INCLUDE (repo_type)"
            );
            // Covering index for browse operations
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_browse ON artifacts (repo_name, name, version) INCLUDE (size, created_date, owner)"
            );
            // Index for path-prefix based locate() queries (group resolution)
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_path_prefix ON artifacts (path_prefix, repo_name) WHERE path_prefix IS NOT NULL"
            );
            // Migration: version_sort_key function + stored generated column.
            // v2: text type with zero-padded segments. Covers numeric versions,
            // dates (YYYY-MM-DD), v-prefixed, bare integers, and git hashes.
            // Only UNKNOWN stays NULL.
            migrateVersionSort(statement);
            // Migration: Add tsvector column for full-text search (B1)
            // Uses 'simple' config to avoid language-specific stemming on artifact names
            try {
                statement.executeUpdate(
                    "ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS search_tokens tsvector"
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to add search_tokens column (may already exist)")
                    .error(ex)
                    .log();
            }
            // GIN index for fast full-text search on search_tokens
            try {
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_artifacts_search ON artifacts USING gin(search_tokens)"
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to create GIN index idx_artifacts_search (may already exist)")
                    .error(ex)
                    .log();
            }
            // Trigger function to auto-populate search_tokens on INSERT/UPDATE.
            // Uses translate() to replace dots, slashes, dashes, and underscores
            // with spaces so each component becomes a separate searchable token.
            // Without this, "auto1.base.test.txt" is one opaque token and
            // searching for "test" won't match.
            try {
                statement.executeUpdate(
                    String.join(
                        "\n",
                        "CREATE OR REPLACE FUNCTION artifacts_search_update() RETURNS trigger AS $$",
                        "BEGIN",
                        "  NEW.search_tokens := to_tsvector('simple',",
                        "    translate(coalesce(NEW.name, ''), './-_', '    ') || ' ' ||",
                        "    translate(coalesce(NEW.version, ''), './-_', '    ') || ' ' ||",
                        "    coalesce(NEW.owner, '') || ' ' ||",
                        "    translate(coalesce(NEW.repo_name, ''), './-_', '    ') || ' ' ||",
                        "    translate(coalesce(NEW.repo_type, ''), './-_', '    '));",
                        "  RETURN NEW;",
                        "END;",
                        "$$ LANGUAGE plpgsql;"
                    )
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to create artifacts_search_update function")
                    .error(ex)
                    .log();
            }
            // Attach trigger to artifacts table (drop first for idempotent re-creation)
            try {
                statement.executeUpdate(
                    "DROP TRIGGER IF EXISTS trg_artifacts_search ON artifacts"
                );
                statement.executeUpdate(
                    String.join(
                        "\n",
                        "CREATE TRIGGER trg_artifacts_search",
                        "  BEFORE INSERT OR UPDATE ON artifacts",
                        "  FOR EACH ROW EXECUTE FUNCTION artifacts_search_update();"
                    )
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to create trigger trg_artifacts_search")
                    .error(ex)
                    .log();
            }
            // Performance indexes identified by full-stack audit
            try {
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_artifacts_name_lower ON artifacts(LOWER(name))"
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to create idx_artifacts_name_lower (may already exist)")
                    .error(ex)
                    .log();
            }
            try {
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_artifacts_repo_latest ON artifacts(repo_name, created_date DESC)"
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to create idx_artifacts_repo_latest (may already exist)")
                    .error(ex)
                    .log();
            }
            // Trigram index for fuzzy search (requires pg_trgm extension)
            try {
                statement.executeUpdate("CREATE EXTENSION IF NOT EXISTS pg_trgm");
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_artifacts_name_trgm ON artifacts USING GIN(name gin_trgm_ops)"
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to create trigram index (pg_trgm extension may not be available)")
                    .error(ex)
                    .log();
            }
            // Covering index for dashboard top-repos query — turns GROUP BY seq scan into
            // an index-only aggregation; required for CONCURRENTLY refresh of mv_artifact_per_repo
            try {
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_artifacts_repo_size_cover "
                        + "ON artifacts(repo_name, repo_type) INCLUDE (size)"
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to create idx_artifacts_repo_size_cover (may already exist)")
                    .error(ex)
                    .log();
            }
            // Materialized views for dashboard aggregates — sub-millisecond reads vs seq scans.
            // Refreshed externally by pg_cron; see docs/admin-guide/installation.md.
            try {
                statement.executeUpdate(
                    "CREATE MATERIALIZED VIEW IF NOT EXISTS mv_artifact_totals AS "
                        + "SELECT COUNT(*) AS artifact_count, "
                        + "COALESCE(SUM(size), 0) AS total_size FROM artifacts"
                );
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.db")
                    .message("Failed to create mv_artifact_totals")
                    .eventCategory("database").eventAction("mv_create").eventOutcome("failure")
                    .error(ex).log();
            }
            // Single-row MV: unique index on constant expression — required for CONCURRENTLY.
            // Kept in a separate try so a pre-existing MV without the index still gets it.
            try {
                statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_mv_artifact_totals "
                        + "ON mv_artifact_totals ((1))"
                );
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.db")
                    .message("Failed to create uq_mv_artifact_totals — REFRESH CONCURRENTLY will not work")
                    .eventCategory("database").eventAction("mv_index_create").eventOutcome("failure")
                    .error(ex).log();
            }
            try {
                statement.executeUpdate(
                    "CREATE MATERIALIZED VIEW IF NOT EXISTS mv_artifact_per_repo AS "
                        + "SELECT repo_name, repo_type, "
                        + "COUNT(*) AS artifact_count, "
                        + "COALESCE(SUM(size), 0) AS total_size "
                        + "FROM artifacts GROUP BY repo_name, repo_type"
                );
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.db")
                    .message("Failed to create mv_artifact_per_repo")
                    .eventCategory("database").eventAction("mv_create").eventOutcome("failure")
                    .error(ex).log();
            }
            try {
                statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_mv_artifact_per_repo "
                        + "ON mv_artifact_per_repo (repo_name, repo_type)"
                );
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_mv_artifact_per_repo_size "
                        + "ON mv_artifact_per_repo (total_size DESC, artifact_count DESC)"
                );
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.db")
                    .message("Failed to create indexes on mv_artifact_per_repo — REFRESH CONCURRENTLY will not work")
                    .eventCategory("database").eventAction("mv_index_create").eventOutcome("failure")
                    .error(ex).log();
            }
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
                    "   installed_by VARCHAR,",
                    "   CONSTRAINT cooldown_artifact_unique UNIQUE (repo_name, artifact, version)",
                    ");"
                )
            );
            // Migration: Add installed_by column if table already exists without it
            statement.executeUpdate(
                "ALTER TABLE artifact_cooldowns ADD COLUMN IF NOT EXISTS installed_by VARCHAR"
            );
            // Migration: Drop parent_block_id if exists (no longer used)
            try {
                statement.executeUpdate(
                    "ALTER TABLE artifact_cooldowns DROP CONSTRAINT IF EXISTS cooldown_parent_fk"
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to drop constraint cooldown_parent_fk (may not exist)")
                    .error(ex)
                    .log();
            }
            try {
                statement.executeUpdate(
                    "ALTER TABLE artifact_cooldowns DROP COLUMN IF EXISTS parent_block_id"
                );
            } catch (final SQLException ex) {
                EcsLogger.debug("com.auto1.pantera.db")
                    .message("Failed to drop column parent_block_id (may not exist)")
                    .error(ex)
                    .log();
            }
            // Migration: Drop artifact_cooldown_attempts table (no longer used)
            statement.executeUpdate(
                "DROP TABLE IF EXISTS artifact_cooldown_attempts"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_cooldowns_repo_artifact ON artifact_cooldowns(repo_name, artifact, version)"
            );
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_cooldowns_status ON artifact_cooldowns(status)"
            );
            // Composite index for paginated active blocks query (ORDER BY blocked_at DESC)
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_cooldowns_status_blocked_at ON artifact_cooldowns(status, blocked_at DESC)"
            );
            // Composite index for per-repo active block counts
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_cooldowns_repo_status ON artifact_cooldowns(repo_type, repo_name, status)"
            );
            // Index for server-side search within active blocks
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_cooldowns_status_artifact ON artifact_cooldowns(status, artifact, repo_name)"
            );
            // Partial index for pg_cron expired-block cleanup query
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_cooldowns_status_blocked_until "
                    + "ON artifact_cooldowns(status, blocked_until) WHERE status = 'ACTIVE'"
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
            throw new PanteraException(error);
        }
    }

    /**
     * Migrate the {@code version_sort} column to v2 (text type with zero-padded
     * segments). Drops any existing v1 {@code bigint[]} column and recreates it
     * as {@code text} backed by the {@code version_sort_key} SQL function.
     *
     * <p>The function handles all version forms:
     * <ul>
     *   <li>Numeric with separators: {@code 1.5.0-SNAPSHOT} → zero-padded segments</li>
     *   <li>Dates: {@code 2019-08-23} → same (hyphens normalized to dots)</li>
     *   <li>v-prefixed: {@code v0.0.3-SNAPSHOT} → v stripped, then normalized</li>
     *   <li>Bare integers: {@code 5007235} → single zero-padded segment</li>
     *   <li>Git hashes / other: stored as-is for lexical sort</li>
     *   <li>{@code UNKNOWN}: NULL (falls to end via NULLS LAST)</li>
     * </ul>
     *
     * @param statement Open JDBC statement
     */
    private static void migrateVersionSort(final Statement statement) {
        // 1. Create the IMMUTABLE helper function (idempotent via OR REPLACE).
        try {
            statement.executeUpdate(
                "CREATE OR REPLACE FUNCTION version_sort_key(v text) RETURNS text "
                    + "IMMUTABLE LANGUAGE sql AS $func$ "
                    + "SELECT CASE "
                    + "  WHEN v IS NULL OR v = 'UNKNOWN' THEN NULL "
                    + "  WHEN REGEXP_REPLACE(v, '^[vV]', '') ~ '^[0-9]+([.-][0-9]+)+' "
                    + "    THEN (SELECT string_agg(lpad(s, 20, '0'), '.') "
                    + "          FROM unnest(string_to_array("
                    + "            REGEXP_REPLACE("
                    + "              REPLACE("
                    + "                REGEXP_REPLACE("
                    + "                  REGEXP_REPLACE(v, '^[vV]', ''),"
                    + "                  '[^0-9.\\-].*$', ''),"
                    + "                '-', '.'),"
                    + "              '(^\\.|\\.\\.+|\\.$)', '', 'g'),"
                    + "            '.')) AS s) "
                    + "  WHEN v ~ '^[0-9]+$' THEN lpad(v, 20, '0') "
                    + "  ELSE v "
                    + "END $func$"
            );
        } catch (final SQLException ex) {
            EcsLogger.warn("com.auto1.pantera.db")
                .message("Failed to create version_sort_key function")
                .error(ex)
                .log();
            return;
        }
        // 2. Detect whether the existing version_sort column is v1 (bigint[]) or
        //    missing. If v1 exists, drop and recreate with v2 (text).
        boolean needRecreate = true;
        try (ResultSet rs = statement.executeQuery(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'artifacts' AND column_name = 'version_sort'"
        )) {
            if (rs.next()) {
                final String type = rs.getString(1);
                // "text" == already migrated; "ARRAY" == v1 bigint[], needs drop
                needRecreate = !"text".equalsIgnoreCase(type);
            }
        } catch (final SQLException ex) {
            EcsLogger.debug("com.auto1.pantera.db")
                .message("Failed to detect version_sort column type")
                .error(ex)
                .log();
        }
        if (needRecreate) {
            try {
                statement.executeUpdate(
                    "ALTER TABLE artifacts DROP COLUMN IF EXISTS version_sort"
                );
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.db")
                    .message("Failed to drop old version_sort column")
                    .error(ex)
                    .log();
            }
            try {
                statement.executeUpdate(
                    "ALTER TABLE artifacts ADD COLUMN version_sort text "
                        + "GENERATED ALWAYS AS (version_sort_key(version)) STORED"
                );
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.db")
                    .message("Failed to add version_sort column")
                    .error(ex)
                    .log();
            }
        }
        // 3. Index for ORDER BY version_sort queries.
        try {
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_version_sort "
                    + "ON artifacts(version_sort)"
            );
        } catch (final SQLException ex) {
            EcsLogger.debug("com.auto1.pantera.db")
                .message("Failed to create idx_artifacts_version_sort")
                .error(ex)
                .log();
        }
    }
}
