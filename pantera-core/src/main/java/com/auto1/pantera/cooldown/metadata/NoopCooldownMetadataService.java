/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown.metadata;

import com.auto1.pantera.cooldown.CooldownInspector;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * No-op implementation of {@link CooldownMetadataService}.
 * Returns raw metadata unchanged. Used when cooldown is disabled.
 *
 * @since 1.0
 */
public final class NoopCooldownMetadataService implements CooldownMetadataService {

    /**
     * Singleton instance.
     */
    public static final NoopCooldownMetadataService INSTANCE = new NoopCooldownMetadataService();

    /**
     * Private constructor.
     */
    private NoopCooldownMetadataService() {
    }

    @Override
    public <T> CompletableFuture<byte[]> filterMetadata(
        final String repoType,
        final String repoName,
        final String packageName,
        final byte[] rawMetadata,
        final MetadataParser<T> parser,
        final MetadataFilter<T> filter,
        final MetadataRewriter<T> rewriter,
        final Optional<CooldownInspector> inspector
    ) {
        // Return raw metadata unchanged
        return CompletableFuture.completedFuture(rawMetadata);
    }

    @Override
    public void invalidate(
        final String repoType,
        final String repoName,
        final String packageName
    ) {
        // No-op
    }

    @Override
    public void invalidateAll(final String repoType, final String repoName) {
        // No-op
    }

    @Override
    public String stats() {
        return "NoopCooldownMetadataService[disabled]";
    }
}
