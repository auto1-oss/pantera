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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.PanteraException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * AssetPath tests.
 * @since 0.1
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class AssetPathTest {
    @Test
    public void getsPath() {
        final AssetPath path = new AssetPath("npm-proxy");
        MatcherAssert.assertThat(
            path.value("/npm-proxy/@vue/vue-cli/-/vue-cli-1.0.0.tgz"),
            new IsEqual<>("@vue/vue-cli/-/vue-cli-1.0.0.tgz")
        );
    }

    @Test
    public void getsPathWithRootContext() {
        final AssetPath path = new AssetPath("");
        MatcherAssert.assertThat(
            path.value("/@vue/vue-cli/-/vue-cli-1.0.0.tgz"),
            new IsEqual<>("@vue/vue-cli/-/vue-cli-1.0.0.tgz")
        );
    }

    @Test
    public void failsByPattern() {
        final AssetPath path = new AssetPath("npm-proxy");
        Assertions.assertThrows(
            PanteraException.class,
            () -> path.value("/npm-proxy/@vue/vue-cli")
        );
    }

    @Test
    public void failsByPrefix() {
        final AssetPath path = new AssetPath("npm-proxy");
        Assertions.assertThrows(
            PanteraException.class,
            () -> path.value("/@vue/vue-cli/-/vue-cli-1.0.0.tgz")
        );
    }
}
