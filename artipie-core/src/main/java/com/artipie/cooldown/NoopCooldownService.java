/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

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
