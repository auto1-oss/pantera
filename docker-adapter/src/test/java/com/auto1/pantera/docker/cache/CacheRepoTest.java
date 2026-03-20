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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.proxy.ProxyRepo;
import com.auto1.pantera.http.ResponseBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Tests for {@link CacheRepo}.
 *
 * @since 0.3
 */
final class CacheRepoTest {

    /**
     * Tested {@link CacheRepo}.
     */
    private CacheRepo repo;

    @BeforeEach
    void setUp() {
        this.repo = new CacheRepo(
            "test",
            new ProxyRepo(
                (line, headers, body) -> ResponseBuilder.ok().completedFuture(),
                "test-origin"
            ),
            new AstoDocker("registry", new InMemoryStorage())
                .repo("test-cache"), Optional.empty(), "*", Optional.empty()
        );
    }

    @Test
    void createsCacheLayers() {
        MatcherAssert.assertThat(
            this.repo.layers(),
            new IsInstanceOf(CacheLayers.class)
        );
    }

    @Test
    void createsCacheManifests() {
        MatcherAssert.assertThat(
            this.repo.manifests(),
            new IsInstanceOf(CacheManifests.class)
        );
    }
}
