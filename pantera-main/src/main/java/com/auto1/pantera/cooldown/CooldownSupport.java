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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilterService;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheConfig;
import com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService;
import com.auto1.pantera.db.dao.SettingsDao;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.trace.TraceContextExecutor;
import com.auto1.pantera.settings.Settings;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.json.JsonObject;

/**
 * Factory for cooldown services.
 */
public final class CooldownSupport {

    /**
     * Pool name for metrics identification.
     */
    public static final String POOL_NAME = "pantera.cooldown";

    /**
     * Default pool size multiplier for cooldown threads.
     * ENTERPRISE: Sized for high-concurrency (1000 req/s target).
     * Pool scales with CPU count to handle concurrent database operations.
     */
    private static final int POOL_SIZE_MULTIPLIER = 4;

    /**
     * Minimum pool size regardless of CPU count.
     */
    private static final int MIN_POOL_SIZE = 8;

    /**
     * Dedicated executor for cooldown operations to avoid exhausting common pool.
     * Pool name: {@value #POOL_NAME} (visible in thread dumps and metrics).
     * Wrapped with TraceContextExecutor to propagate MDC (trace.id, user, etc.) to cooldown threads.
     *
     * <p>ENTERPRISE SIZING: Pool is sized for high concurrency (target 1000 req/s).
     * With synchronous JDBC calls, each blocked thread handles one request.
     * Default: max(8, CPU * 4) threads to handle concurrent cooldown evaluations.</p>
     */
    private static final ExecutorService COOLDOWN_EXECUTOR = TraceContextExecutor.wrap(
        Executors.newFixedThreadPool(
            Math.max(MIN_POOL_SIZE, Runtime.getRuntime().availableProcessors() * POOL_SIZE_MULTIPLIER),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, POOL_NAME + ".worker-" + counter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            }
        )
    );

    private CooldownSupport() {
    }

    public static CooldownService create(final Settings settings) {
        return create(settings, COOLDOWN_EXECUTOR);
    }

    public static CooldownService create(final Settings settings, final Executor executor) {
        // Register all adapter bundles (parser/filter/rewriter/detector/responseFactory)
        // into the global CooldownAdapterRegistry. This is idempotent and safe to call
        // early -- the registry is a ConcurrentHashMap, and adapters are stateless.
        CooldownWiring.registerAllAdapters();
        return settings.artifactsDatabase()
            .map(ds -> {
                // Load DB-persisted cooldown config and apply over YAML defaults.
                // This ensures overrides saved via the UI survive container restarts.
                loadDbCooldownSettings(settings.cooldown(), ds);
                EcsLogger.info("com.auto1.pantera.cooldown")
                    .message("Creating JdbcCooldownService (enabled: " + settings.cooldown().enabled() + ", min age: " + settings.cooldown().minimumAllowedAge().toString() + ")")
                    .eventCategory("configuration")
                    .eventAction("cooldown_init")
                    .eventOutcome("success")
                    .log();
                final JdbcCooldownService service = new JdbcCooldownService(
                    settings.cooldown(),
                    new CooldownRepository(ds),
                    executor
                );
                // Initialize metrics from database (async) - loads actual active block counts
                service.initializeMetrics();
                return (CooldownService) service;
            })
            .orElseGet(() -> {
                EcsLogger.warn("com.auto1.pantera.cooldown")
                    .message("No artifacts database configured - using NoopCooldownService (cooldown disabled)")
                    .eventCategory("configuration")
                    .eventAction("cooldown_init")
                    .eventOutcome("failure")
                    .log();
                return NoopCooldownService.INSTANCE;
            });
    }

    /**
     * Create a CooldownMetadataService for filtering package metadata.
     *
     * @param cooldownService The cooldown service for evaluations
     * @param settings Application settings
     * @return Metadata service (Noop if cooldown disabled)
     */
    public static CooldownMetadataService createMetadataService(
        final CooldownService cooldownService,
        final Settings settings
    ) {
        if (cooldownService instanceof NoopCooldownService) {
            return NoopCooldownMetadataService.INSTANCE;
        }
        // Get cooldown settings and cache from the JdbcCooldownService
        if (!(cooldownService instanceof JdbcCooldownService)) {
            return NoopCooldownMetadataService.INSTANCE;
        }
        final JdbcCooldownService jdbc = (JdbcCooldownService) cooldownService;
        
        // Create metadata cache with configuration and Valkey L2 if available
        final FilteredMetadataCacheConfig cacheConfig = FilteredMetadataCacheConfig.getInstance();
        final FilteredMetadataCache metadataCache = 
            com.auto1.pantera.cache.GlobalCacheConfig.valkeyConnection()
                .map(valkey -> new FilteredMetadataCache(cacheConfig, valkey))
                .orElseGet(() -> new FilteredMetadataCache(cacheConfig, null));
        
        EcsLogger.info("com.auto1.pantera.cooldown")
            .message("Created CooldownMetadataService with config=" + cacheConfig + 
                ", L2=" + (com.auto1.pantera.cache.GlobalCacheConfig.valkeyConnection().isPresent() ? "Valkey" : "none") +
                ", L2OnlyMode=" + metadataCache.isL2OnlyMode())
            .eventCategory("configuration")
            .eventAction("metadata_service_init")
            .log();
        
        final MetadataFilterService metadataService = new MetadataFilterService(
            cooldownService,
            settings.cooldown(),
            jdbc.cache(),
            metadataCache,
            COOLDOWN_EXECUTOR,
            50 // max versions to evaluate
        );
        // Wire the metadata-cache invalidation callback so that when
        // a cooldown block EXPIRES naturally, the filtered metadata
        // cache is flushed. Without this, clients see stale metadata
        // (with the version stripped out) until the cache TTL expires
        // — which could be hours, and L2 purge doesn't clear L1.
        jdbc.setOnBlockRemoved((repoType, repoName, artifact, version) -> {
            try {
                metadataService.invalidate(repoType, repoName, artifact);
            } catch (final Exception err) {
                EcsLogger.warn("com.auto1.pantera.cooldown")
                    .message("onBlockRemoved: metadata invalidation failed")
                    .eventCategory("database")
                    .eventAction("metadata_cache_invalidate")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .error(err)
                    .log();
            }
        });
        // Wire envelope invalidation back into the cooldown service so block
        // state changes invalidate cached filtered-metadata envelopes eagerly
        // instead of letting them go stale for up to the L2 TTL (10 minutes).
        // This covers new blocks (createBlockInDatabase), bulk mark
        // (markAllBlocked), bulk unmark (unmarkAllBlockedPackage / ForRepo),
        // and manual archive (archiveAndDelete via expire()).
        jdbc.setEnvelopeInvalidator(metadataCache);
        return metadataService;
    }

    /**
     * Extract the CooldownCache from a CooldownService, if it is backed
     * by JdbcCooldownService. Returns null for NoopCooldownService.
     *
     * @param cooldownService The cooldown service
     * @return CooldownCache or null
     */
    public static CooldownCache extractCache(final CooldownService cooldownService) {
        if (cooldownService instanceof JdbcCooldownService) {
            return ((JdbcCooldownService) cooldownService).cache();
        }
        return null;
    }

    /**
     * Load cooldown settings from DB and apply to in-memory CooldownSettings.
     * DB settings (saved via the UI) take precedence over YAML defaults.
     * @param csettings In-memory cooldown settings to update
     * @param ds Database data source
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    static void loadDbCooldownSettings(
        final CooldownSettings csettings,
        final javax.sql.DataSource ds
    ) {
        try {
            final SettingsDao dao = new SettingsDao(ds);
            final Optional<JsonObject> dbConfig = dao.get("cooldown");
            if (dbConfig.isEmpty()) {
                return;
            }
            final JsonObject cfg = dbConfig.get();
            final boolean enabled = cfg.getBoolean("enabled", csettings.enabled());
            final Duration minAge = cfg.containsKey("minimum_allowed_age")
                ? parseDuration(cfg.getString("minimum_allowed_age"))
                : csettings.minimumAllowedAge();
            final Map<String, CooldownSettings.RepoTypeConfig> overrides = new HashMap<>();
            if (cfg.containsKey("repo_types")) {
                final JsonObject repoTypes = cfg.getJsonObject("repo_types");
                for (final String key : repoTypes.keySet()) {
                    final JsonObject rt = repoTypes.getJsonObject(key);
                    overrides.put(
                        key.toLowerCase(Locale.ROOT),
                        new CooldownSettings.RepoTypeConfig(
                            rt.getBoolean("enabled", true),
                            rt.containsKey("minimum_allowed_age")
                                ? parseDuration(rt.getString("minimum_allowed_age"))
                                : minAge
                        )
                    );
                }
            }
            final int historyRetentionDays = cfg.getInt(
                "history_retention_days", csettings.historyRetentionDays()
            );
            final int cleanupBatchLimit = cfg.getInt(
                "cleanup_batch_limit", csettings.cleanupBatchLimit()
            );
            // If out-of-range values are present in the blob, update() throws and
            // the outer catch logs it as a load failure — YAML defaults apply.
            csettings.update(
                enabled, minAge, overrides,
                historyRetentionDays, cleanupBatchLimit
            );
            EcsLogger.info("com.auto1.pantera.cooldown")
                .message("Loaded cooldown settings from database (enabled: "
                    + enabled + ", overrides: " + overrides.size()
                    + ", history_retention_days: " + historyRetentionDays
                    + ", cleanup_batch_limit: " + cleanupBatchLimit + ")")
                .eventCategory("configuration")
                .eventAction("cooldown_db_load")
                .log();
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.cooldown")
                .message("Failed to load cooldown settings from DB, using YAML defaults: "
                    + ex.getMessage())
                .eventCategory("configuration")
                .eventAction("cooldown_db_load")
                .eventOutcome("failure")
                .log();
        }
    }

    /**
     * Parse duration string (e.g. "7d", "24h", "30m") to Duration.
     * @param value Duration string
     * @return Duration
     */
    private static Duration parseDuration(final String value) {
        if (value == null || value.isEmpty()) {
            return Duration.ofHours(CooldownSettings.DEFAULT_HOURS);
        }
        final String trimmed = value.trim().toLowerCase(Locale.ROOT);
        final String num = trimmed.replaceAll("[^0-9]", "");
        if (num.isEmpty()) {
            return Duration.ofHours(CooldownSettings.DEFAULT_HOURS);
        }
        final long amount = Long.parseLong(num);
        if (trimmed.endsWith("d")) {
            return Duration.ofDays(amount);
        } else if (trimmed.endsWith("h")) {
            return Duration.ofHours(amount);
        } else if (trimmed.endsWith("m")) {
            return Duration.ofMinutes(amount);
        }
        return Duration.ofHours(amount);
    }
}
