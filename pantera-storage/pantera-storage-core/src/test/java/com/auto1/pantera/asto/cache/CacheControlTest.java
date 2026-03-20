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
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Key;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test case for {@link CacheControl}.
 *
 * @since 0.25
 */
final class CacheControlTest {

    static Object[][] verifyAllItemsParams() {
        return new Object[][]{
            new Object[]{CacheControl.Standard.ALWAYS, CacheControl.Standard.ALWAYS, true},
            new Object[]{CacheControl.Standard.ALWAYS, CacheControl.Standard.NO_CACHE, false},
            new Object[]{CacheControl.Standard.NO_CACHE, CacheControl.Standard.ALWAYS, false},
            new Object[]{CacheControl.Standard.NO_CACHE, CacheControl.Standard.NO_CACHE, false},
        };
    }

    @ParameterizedTest
    @MethodSource("verifyAllItemsParams")
    void verifyAllItems(final CacheControl first, final CacheControl second,
        final boolean expects) throws Exception {
        MatcherAssert.assertThat(
            new CacheControl.All(first, second)
                .validate(Key.ROOT, Remote.EMPTY)
                .toCompletableFuture().get(),
            Matchers.is(expects)
        );
    }
}
