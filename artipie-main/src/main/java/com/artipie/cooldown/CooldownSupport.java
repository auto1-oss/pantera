/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import com.artipie.cooldown.NoopCooldownService;
import com.artipie.cooldown.CooldownService;
import com.artipie.settings.Settings;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Factory for cooldown services.
 */
public final class CooldownSupport {

    private CooldownSupport() {
    }

    public static CooldownService create(final Settings settings) {
        return create(settings, ForkJoinPool.commonPool());
    }

    public static CooldownService create(final Settings settings, final Executor executor) {
        return settings.artifactsDatabase()
            .map(ds -> (CooldownService) new JdbcCooldownService(
                settings.cooldown(),
                new CooldownRepository(ds),
                executor
            ))
            .orElse(NoopCooldownService.INSTANCE);
    }
}
