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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * TTL-based cache control for Go module metadata.
 * Validates cached content by checking if it has expired based on the configured TTL.
 * 
 * <p>This is used for metadata files like {@code @v/list} (version lists) and
 * {@code @latest} which need to be refreshed periodically to pick up new versions
 * from upstream. Artifacts ({@code .info}, {@code .mod}, {@code .zip}) are immutable
 * and should use checksum-based validation or {@link CacheControl.Standard#ALWAYS}.</p>
 *
 * @since 1.0
 */
public final class CacheTimeControl implements CacheControl {

    /**
     * Default metadata TTL: 12 hours.
     */
    public static final Duration DEFAULT_TTL = Duration.ofHours(12);

    /**
     * Time during which the cached content is valid.
     */
    private final Duration expiration;

    /**
     * Storage to check metadata timestamps.
     */
    private final Storage storage;

    /**
     * Ctor with default TTL of 12 hours.
     * @param storage Storage
     */
    public CacheTimeControl(final Storage storage) {
        this(storage, DEFAULT_TTL);
    }

    /**
     * Ctor.
     * @param storage Storage
     * @param expiration Time after which cached items are not valid
     */
    public CacheTimeControl(final Storage storage, final Duration expiration) {
        this.storage = storage;
        this.expiration = expiration;
    }

    @Override
    public CompletionStage<Boolean> validate(final Key item, final Remote content) {
        return this.storage.exists(item)
            .thenCompose(
                exists -> {
                    if (exists) {
                        return this.storage.metadata(item)
                            .thenApply(
                                // Valid if younger than the TTL. A storage that
                                // reports no updated-at gives no evidence of
                                // freshness: stale (refetch; the cached copy
                                // still serves if upstream fails), never fresh
                                // forever.
                                metadata -> metadata.read(Meta.OP_UPDATED_AT)
                                    .map(
                                        updated -> Duration.between(updated, Instant.now())
                                            .compareTo(this.expiration) < 0
                                    )
                                    .orElse(false)
                            );
                    }
                    // Item doesn't exist - not valid (will fetch from remote)
                    return CompletableFuture.completedFuture(false);
                }
            );
    }
}

