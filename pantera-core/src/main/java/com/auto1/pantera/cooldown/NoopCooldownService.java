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
package com.auto1.pantera.cooldown;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Cooldown service that always allows requests.
 */
public final class NoopCooldownService implements CooldownService {

    public static final NoopCooldownService INSTANCE = new NoopCooldownService();

    private NoopCooldownService() {
    }

    @Override
    public CompletableFuture<CooldownResult> evaluate(
        final CooldownRequest request,
        final CooldownInspector inspector
    ) {
        return CompletableFuture.completedFuture(CooldownResult.allowed());
    }

    @Override
    public CompletableFuture<Void> unblock(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final String actor
    ) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unblockAll(
        final String repoType,
        final String repoName,
        final String actor
    ) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<CooldownBlock>> activeBlocks(
        final String repoType,
        final String repoName
    ) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
