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
package com.auto1.pantera.publishdate;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pins the Track 5 Phase 2A invariant: a {@link RegistryBackedInspector}
 * constructed with {@link PublishDateRegistry.Mode#CACHE_ONLY} forwards
 * exactly that mode to the registry, so the registry never reaches the
 * upstream-source step on cache-hit call sites. The default constructor
 * stays on {@code NETWORK_FALLBACK} for back-compat with pre-Track-5
 * callers.
 *
 * @since 2.2.0
 */
final class RegistryBackedInspectorCacheOnlyTest {

    @Test
    @DisplayName("CACHE_ONLY constructor forwards the mode to the registry")
    void cacheOnlyForwardsMode() {
        final AtomicReference<PublishDateRegistry.Mode> received = new AtomicReference<>();
        final PublishDateRegistry registry = new PublishDateRegistry() {
            @Override
            public CompletableFuture<Optional<Instant>> publishDate(
                final String repoType, final String name, final String version
            ) {
                return this.publishDate(
                    repoType, name, version, PublishDateRegistry.Mode.NETWORK_FALLBACK
                );
            }

            @Override
            public CompletableFuture<Optional<Instant>> publishDate(
                final String repoType, final String name, final String version,
                final PublishDateRegistry.Mode mode
            ) {
                received.set(mode);
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
        final RegistryBackedInspector inspector = new RegistryBackedInspector(
            "maven", registry, PublishDateRegistry.Mode.CACHE_ONLY
        );
        inspector.releaseDate("foo", "1.0").join();
        MatcherAssert.assertThat(
            "inspector forwarded CACHE_ONLY to the registry",
            received.get(),
            new IsEqual<>(PublishDateRegistry.Mode.CACHE_ONLY)
        );
    }

    @Test
    @DisplayName("default constructor forwards NETWORK_FALLBACK (pre-Track-5 behaviour)")
    void defaultForwardsNetworkFallback() {
        final AtomicReference<PublishDateRegistry.Mode> received = new AtomicReference<>();
        final PublishDateRegistry registry = new PublishDateRegistry() {
            @Override
            public CompletableFuture<Optional<Instant>> publishDate(
                final String repoType, final String name, final String version
            ) {
                return this.publishDate(
                    repoType, name, version, PublishDateRegistry.Mode.NETWORK_FALLBACK
                );
            }

            @Override
            public CompletableFuture<Optional<Instant>> publishDate(
                final String repoType, final String name, final String version,
                final PublishDateRegistry.Mode mode
            ) {
                received.set(mode);
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
        final RegistryBackedInspector inspector = new RegistryBackedInspector(
            "maven", registry
        );
        inspector.releaseDate("foo", "1.0").join();
        MatcherAssert.assertThat(
            "default constructor uses NETWORK_FALLBACK",
            received.get(),
            new IsEqual<>(PublishDateRegistry.Mode.NETWORK_FALLBACK)
        );
    }
}
