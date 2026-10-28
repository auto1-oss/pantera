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
package com.auto1.pantera.settings.cache;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.cache.StoragesCache;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PanteraCaches.All}.
 * <p>
 * Regression guard for WS2.3 (2.3.0): {@code policyCache()} used to derive a
 * fresh {@link Cleanable} by {@code instanceof}-checking the raw
 * {@code Policy} on every call, bypassing whatever cross-node-publishing
 * wrapper the caller had prepared. It must now be a straight pass-through of
 * whatever {@link Cleanable} the caller supplies — {@code YamlSettings} is
 * the one responsible for deciding whether that's the raw policy or a
 * {@code PublishingCleanable} wrapper.
 *
 * @since 2.3.0
 */
final class PanteraCachesAllTest {

    @Test
    void policyCacheReturnsExactlyWhatWasSupplied() {
        final AtomicInteger invalidations = new AtomicInteger();
        final Cleanable<String> supplied = new Cleanable<>() {
            @Override
            public void invalidate(final String key) {
                invalidations.incrementAndGet();
            }

            @Override
            public void invalidateAll() {
                invalidations.incrementAndGet();
            }
        };
        final PanteraCaches caches = new PanteraCaches.All(
            supplied, new StoragesCache(), supplied, new GuavaFiltersCache()
        );
        MatcherAssert.assertThat(
            "policyCache() must return the exact instance the caller supplied",
            caches.policyCache(),
            Matchers.sameInstance(supplied)
        );
        caches.policyCache().invalidate("role-x");
        MatcherAssert.assertThat(
            "Invalidation on the accessor must reach the supplied instance",
            invalidations.get(),
            Matchers.is(1)
        );
    }
}
