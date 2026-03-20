/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Check if saved item is expired by comparing time value.
 */
final class CacheTimeControl implements CacheControl {
    /**
     * Name to file which contains info about cached items (e.g. when an item was saved).
     */
    static final Key CACHE_FILE = new Key.From("cache-info.json");

    /**
     * Time during which the file is valid.
     */
    private final Duration expiration;

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Ctor with default value for time of expiration (12 hours).
     * @param storage Storage
     */
    CacheTimeControl(final Storage storage) {
        this(storage, Duration.ofHours(12));
    }

    /**
     * Ctor.
     * @param storage Storage
     * @param expiration Time after which cached items are not valid
     */
    CacheTimeControl(final Storage storage, final Duration expiration) {
        this.storage = storage;
        this.expiration = expiration;
    }

    @Override
    public CompletionStage<Boolean> validate(final Key item, final Remote content) {
        // Use file metadata (last modified time) instead of separate cache-info.json
        // This avoids lock contention and is more reliable
        return this.storage.exists(item)
            .thenCompose(
                exists -> {
                    final CompletionStage<Boolean> res;
                    if (exists) {
                        res = this.storage.metadata(item)
                            .thenApply(
                                metadata -> {
                                    // Try to get last updated time from filesystem
                                    final Instant updatedAt = metadata.read(
                                        raw -> {
                                            if (raw.containsKey("updated-at")) {
                                                return Instant.parse(raw.get("updated-at"));
                                            }
                                            // Fallback: assume valid if no timestamp
                                            return Instant.now();
                                        }
                                    );
                                    final Duration age = Duration.between(updatedAt, Instant.now());
                                    final boolean valid = age.compareTo(this.expiration) < 0;
                                    return valid;
                                }
                            );
                    } else {
                        res = CompletableFuture.completedFuture(false);
                    }
                    return res;
                }
            );
    }

    /**
     * Validate time by comparing difference with time of expiration.
     * @param time Time of uploading
     * @return True is valid as not expired yet, false otherwise.
     */
    private boolean notExpired(final String time) {
        return !Duration.between(
            Instant.now().atZone(ZoneOffset.UTC),
            ZonedDateTime.parse(time)
        ).plus(this.expiration)
        .isNegative();
    }
}
