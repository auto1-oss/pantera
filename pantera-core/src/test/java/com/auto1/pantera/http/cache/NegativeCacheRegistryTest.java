/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link NegativeCacheRegistry}.
 */
class NegativeCacheRegistryTest {

    @AfterEach
    void tearDown() {
        NegativeCacheRegistry.instance().clear();
    }

    @Test
    void registersAndCountsCaches() {
        final NegativeCacheRegistry reg = NegativeCacheRegistry.instance();
        final NegativeCache cache1 = new NegativeCache("maven", "central");
        final NegativeCache cache2 = new NegativeCache("npm", "proxy");
        reg.register("maven", "central", cache1);
        reg.register("npm", "proxy", cache2);
        assertThat(reg.size(), equalTo(2));
    }

    @Test
    void unregistersCache() {
        final NegativeCacheRegistry reg = NegativeCacheRegistry.instance();
        reg.register("maven", "central", new NegativeCache("maven", "central"));
        reg.unregister("maven", "central");
        assertThat(reg.size(), equalTo(0));
    }

    @Test
    void clearRemovesAll() {
        final NegativeCacheRegistry reg = NegativeCacheRegistry.instance();
        reg.register("a", "b", new NegativeCache("a", "b"));
        reg.register("c", "d", new NegativeCache("c", "d"));
        reg.clear();
        assertThat(reg.size(), equalTo(0));
    }
}
