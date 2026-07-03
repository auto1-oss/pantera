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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Track 3 "always-verify" invariant: constructing a Maven
 * {@link CachedProxySlice} without raw {@link Storage} must fail loudly at
 * construction rather than silently falling back to an unverified cache
 * path. The fallback was the only way a primary artifact could land in
 * cache without first being verified against upstream's {@code .sha1},
 * which was itself a contributor to stale / mismatched checksum pairs
 * during long-lived caches.
 *
 * @since 2.2.0
 */
final class CachedProxySliceAlwaysVerifyTest {

    @Test
    @DisplayName("constructor refuses Optional.empty() storage")
    void rejectsEmptyStorage() {
        final Queue<ProxyArtifactEvent> events = new LinkedBlockingQueue<>();
        final IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> new CachedProxySlice(
                new SliceSimple(ResponseBuilder.ok().build()),
                (key, supplier, control) -> supplier.get(),
                Optional.of(events),
                "test-repo",
                "https://repo.maven.apache.org/maven2",
                "maven-proxy",
                NoopCooldownService.INSTANCE,
                trivialInspector(),
                Optional.empty()
            )
        );
        assertTrue(
            ex.getMessage().contains("requires raw storage"),
            "error message must explain the misconfiguration: " + ex.getMessage()
        );
    }

    private static CooldownInspector trivialInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
