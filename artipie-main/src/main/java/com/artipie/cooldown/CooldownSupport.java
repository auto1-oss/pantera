/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import com.artipie.cooldown.NoopCooldownService;
import com.artipie.cooldown.CooldownService;
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
     * Dedicated executor for cooldown operations to avoid exhausting common pool.
     */
    private static final ExecutorService COOLDOWN_EXECUTOR = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "cooldown-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        }
    );

    private CooldownSupport() {
    }

    public static CooldownService create(final Settings settings) {
        return create(settings, COOLDOWN_EXECUTOR);
    }

    public static CooldownService create(final Settings settings, final Executor executor) {
        return settings.artifactsDatabase()
            .map(ds -> {
                com.jcabi.log.Logger.info(
                    CooldownSupport.class,
                    "Creating JdbcCooldownService with settings: enabled=%s, minAge=%s",
                    settings.cooldown().enabled(),
                    settings.cooldown().minimumAllowedAge()
                );
                return (CooldownService) new JdbcCooldownService(
                    settings.cooldown(),
                    new CooldownRepository(ds),
                    executor
                );
            })
            .orElseGet(() -> {
                com.jcabi.log.Logger.warn(
                    CooldownSupport.class,
                    "No artifacts database configured - using NoopCooldownService (cooldown disabled)"
                );
                return NoopCooldownService.INSTANCE;
            });
    }
}
