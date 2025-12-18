/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import com.artipie.cooldown.NoopCooldownService;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.metadata.CooldownMetadataService;
import com.artipie.cooldown.metadata.CooldownMetadataServiceImpl;
import com.artipie.cooldown.metadata.FilteredMetadataCache;
import com.artipie.cooldown.metadata.FilteredMetadataCacheConfig;
import com.artipie.cooldown.metadata.NoopCooldownMetadataService;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.trace.TraceContextExecutor;
import com.artipie.settings.Settings;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for cooldown services.
 */
public final class CooldownSupport {

    /**
     * Pool name for metrics identification.
     */
    public static final String POOL_NAME = "artipie.cooldown";

    /**
     * Dedicated executor for cooldown operations to avoid exhausting common pool.
     * Pool name: {@value #POOL_NAME} (visible in thread dumps and metrics).
     * Wrapped with TraceContextExecutor to propagate MDC (trace.id, user, etc.) to cooldown threads.
     */
    private static final ExecutorService COOLDOWN_EXECUTOR = TraceContextExecutor.wrap(
        Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
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
        return settings.artifactsDatabase()
            .map(ds -> {
                EcsLogger.info("com.artipie.cooldown")
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
                EcsLogger.warn("com.artipie.cooldown")
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
            com.artipie.cache.GlobalCacheConfig.valkeyConnection()
                .map(valkey -> new FilteredMetadataCache(cacheConfig, valkey))
                .orElseGet(() -> new FilteredMetadataCache(cacheConfig, null));
        
        EcsLogger.info("com.artipie.cooldown")
            .message("Created CooldownMetadataService with config=" + cacheConfig + 
                ", L2=" + (com.artipie.cache.GlobalCacheConfig.valkeyConnection().isPresent() ? "Valkey" : "none") +
                ", L2OnlyMode=" + metadataCache.isL2OnlyMode())
            .eventCategory("configuration")
            .eventAction("metadata_service_init")
            .log();
        
        return new CooldownMetadataServiceImpl(
            cooldownService,
            settings.cooldown(),
            jdbc.cache(),
            metadataCache,
            COOLDOWN_EXECUTOR,
            50 // max versions to evaluate
        );
    }
}
