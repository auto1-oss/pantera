/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Key;
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
                                metadata -> {
                                    // Try to get last updated time from storage metadata
                                    final Instant updatedAt = metadata.read(
                                        raw -> {
                                            if (raw.containsKey("updated-at")) {
                                                return Instant.parse(raw.get("updated-at"));
                                            }
                                            // Fallback: assume valid if no timestamp
                                            // This ensures backward compatibility with existing cache
                                            // and allows fallback to stale cache when remote fails
                                            return null;
                                        }
                                    );
                                    if (updatedAt == null) {
                                        // No timestamp - consider valid (backward compatible)
                                        return true;
                                    }
                                    final Duration age = Duration.between(updatedAt, Instant.now());
                                    // Valid if age is less than expiration TTL
                                    return age.compareTo(this.expiration) < 0;
                                }
                            );
                    }
                    // Item doesn't exist - not valid (will fetch from remote)
                    return CompletableFuture.completedFuture(false);
                }
            );
    }
}

