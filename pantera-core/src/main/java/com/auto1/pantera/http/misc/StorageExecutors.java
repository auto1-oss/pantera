/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.misc;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Named thread pools for storage operations, separated by operation type.
 * Prevents slow writes from starving fast reads by providing independent pools.
 *
 * <p>Pool sizing (configurable via environment variables):
 * <ul>
 *   <li>READ: PANTERA_IO_READ_THREADS, default 4x CPUs</li>
 *   <li>WRITE: PANTERA_IO_WRITE_THREADS, default 2x CPUs</li>
 *   <li>LIST: PANTERA_IO_LIST_THREADS, default 1x CPUs</li>
 * </ul>
 *
 * @since 1.20.13
 */
public final class StorageExecutors {

    /**
     * Thread pool for storage read operations (value, exists, metadata).
     */
    public static final ExecutorService READ = Executors.newFixedThreadPool(
        ConfigDefaults.getInt(
            "PANTERA_IO_READ_THREADS",
            Runtime.getRuntime().availableProcessors() * 4
        ),
        namedThreadFactory("artipie-io-read-%d")
    );

    /**
     * Thread pool for storage write operations (save, move, delete).
     */
    public static final ExecutorService WRITE = Executors.newFixedThreadPool(
        ConfigDefaults.getInt(
            "PANTERA_IO_WRITE_THREADS",
            Runtime.getRuntime().availableProcessors() * 2
        ),
        namedThreadFactory("artipie-io-write-%d")
    );

    /**
     * Thread pool for storage list operations.
     */
    public static final ExecutorService LIST = Executors.newFixedThreadPool(
        ConfigDefaults.getInt(
            "PANTERA_IO_LIST_THREADS",
            Runtime.getRuntime().availableProcessors()
        ),
        namedThreadFactory("artipie-io-list-%d")
    );

    private StorageExecutors() {
        // Utility class
    }

    /**
     * Register pool utilization metrics gauges with the given meter registry.
     * Registers active thread count and queue size for each pool (READ, WRITE, LIST).
     * @param registry Micrometer meter registry
     */
    public static void registerMetrics(final MeterRegistry registry) {
        Gauge.builder(
            "pantera.pool.read.active", READ,
            pool -> ((ThreadPoolExecutor) pool).getActiveCount()
        ).description("Active threads in READ pool").register(registry);
        Gauge.builder(
            "pantera.pool.write.active", WRITE,
            pool -> ((ThreadPoolExecutor) pool).getActiveCount()
        ).description("Active threads in WRITE pool").register(registry);
        Gauge.builder(
            "pantera.pool.list.active", LIST,
            pool -> ((ThreadPoolExecutor) pool).getActiveCount()
        ).description("Active threads in LIST pool").register(registry);
        Gauge.builder(
            "pantera.pool.read.queue", READ,
            pool -> ((ThreadPoolExecutor) pool).getQueue().size()
        ).description("Queue size of READ pool").register(registry);
        Gauge.builder(
            "pantera.pool.write.queue", WRITE,
            pool -> ((ThreadPoolExecutor) pool).getQueue().size()
        ).description("Queue size of WRITE pool").register(registry);
        Gauge.builder(
            "pantera.pool.list.queue", LIST,
            pool -> ((ThreadPoolExecutor) pool).getQueue().size()
        ).description("Queue size of LIST pool").register(registry);
    }

    /**
     * Shutdown all storage executor pools and await termination.
     * Should be called during application shutdown.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static void shutdown() {
        READ.shutdown();
        WRITE.shutdown();
        LIST.shutdown();
        try {
            if (!READ.awaitTermination(5, TimeUnit.SECONDS)) {
                READ.shutdownNow();
            }
            if (!WRITE.awaitTermination(5, TimeUnit.SECONDS)) {
                WRITE.shutdownNow();
            }
            if (!LIST.awaitTermination(5, TimeUnit.SECONDS)) {
                LIST.shutdownNow();
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            READ.shutdownNow();
            WRITE.shutdownNow();
            LIST.shutdownNow();
        }
    }

    /**
     * Create a named daemon thread factory.
     * @param nameFormat Thread name format with %d placeholder
     * @return Thread factory
     */
    private static ThreadFactory namedThreadFactory(final String nameFormat) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(final Runnable r) {
                final Thread thread = new Thread(r);
                thread.setName(
                    String.format(nameFormat, this.counter.getAndIncrement())
                );
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
