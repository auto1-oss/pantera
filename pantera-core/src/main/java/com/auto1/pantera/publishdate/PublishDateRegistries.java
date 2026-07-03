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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Global accessor for the runtime PublishDateRegistry. Installed once at startup. */
public final class PublishDateRegistries {

    private static volatile PublishDateRegistry instance = (rt, n, v) ->
        CompletableFuture.completedFuture(Optional.empty());

    private PublishDateRegistries() {
    }

    public static void installDefault(final PublishDateRegistry reg) {
        if (reg == null) {
            throw new IllegalArgumentException("registry");
        }
        instance = reg;
    }

    public static PublishDateRegistry instance() {
        return instance;
    }
}
