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

import com.auto1.pantera.cooldown.api.CooldownDependency;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RegistryBackedInspectorTest {

    @Test
    void releaseDateDelegatesToRegistryWithCorrectRepoType() throws Exception {
        final AtomicReference<String> capturedType = new AtomicReference<>();
        final PublishDateRegistry stub = (rt, n, v) -> {
            capturedType.set(rt);
            return CompletableFuture.completedFuture(
                Optional.of(Instant.parse("2020-01-01T00:00:00Z"))
            );
        };
        final RegistryBackedInspector insp = new RegistryBackedInspector("maven", stub);

        final Optional<Instant> result = insp.releaseDate(
            "org.apache.commons.commons-lang3", "3.12.0"
        ).get();
        assertEquals(Optional.of(Instant.parse("2020-01-01T00:00:00Z")), result);
        assertEquals("maven", capturedType.get());
    }

    @Test
    void dependenciesAlwaysReturnsEmpty() throws Exception {
        final PublishDateRegistry stub =
            (rt, n, v) -> CompletableFuture.completedFuture(Optional.empty());
        final RegistryBackedInspector insp = new RegistryBackedInspector("npm", stub);

        final List<CooldownDependency> deps = insp.dependencies("anything", "1.0").get();
        assertTrue(deps.isEmpty());
    }

    @Test
    void emptyResultPassesThrough() throws Exception {
        final PublishDateRegistry stub =
            (rt, n, v) -> CompletableFuture.completedFuture(Optional.empty());
        final RegistryBackedInspector insp = new RegistryBackedInspector("pypi", stub);

        assertEquals(Optional.empty(), insp.releaseDate("requests", "2.31.0").get());
    }
}
