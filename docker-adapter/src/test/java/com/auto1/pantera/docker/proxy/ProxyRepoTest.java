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
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.http.ResponseBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProxyRepo}.
 *
 * @since 0.3
 */
final class ProxyRepoTest {

    @Test
    void createsProxyLayers() {
        final ProxyRepo docker = new ProxyRepo(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(),
            "test"
        );
        MatcherAssert.assertThat(
            docker.layers(),
            new IsInstanceOf(ProxyLayers.class)
        );
    }

    @Test
    void createsProxyManifests() {
        final ProxyRepo docker = new ProxyRepo(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(),
            "my-repo"
        );
        MatcherAssert.assertThat(
            docker.manifests(),
            new IsInstanceOf(ProxyManifests.class)
        );
    }
}
