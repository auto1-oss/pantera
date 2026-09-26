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
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link CacheTimeControl}: freshness is judged from the storage's
 * {@code updated-at} metadata, and an entry whose storage reports no
 * timestamp (S3 before 2.2.9, vertx-file, in-memory) is treated as stale —
 * never as fresh forever.
 *
 * @since 2.2.9
 */
final class CacheTimeControlTest {

    /**
     * Cached item key.
     */
    private static final Key ITEM = new Key.From("golang.org/x/text/@v/list");

    @Test
    void freshWhenUpdatedWithinTtl() {
        MatcherAssert.assertThat(
            CacheTimeControlTest.validate(CacheTimeControlTest.stamped(Instant.now())),
            new IsEqual<>(true)
        );
    }

    @Test
    void staleWhenUpdatedBeforeTtl() {
        MatcherAssert.assertThat(
            CacheTimeControlTest.validate(
                CacheTimeControlTest.stamped(Instant.now().minus(Duration.ofHours(13)))
            ),
            new IsEqual<>(false)
        );
    }

    @Test
    void staleWhenStorageReportsNoTimestamp() {
        final Storage storage = new InMemoryStorage();
        new BlockingStorage(storage).save(ITEM, "cached".getBytes(StandardCharsets.UTF_8));
        MatcherAssert.assertThat(
            CacheTimeControlTest.validate(storage),
            new IsEqual<>(false)
        );
    }

    @Test
    void staleWhenAbsent() {
        MatcherAssert.assertThat(
            CacheTimeControlTest.validate(new InMemoryStorage()),
            new IsEqual<>(false)
        );
    }

    private static boolean validate(final Storage storage) {
        return new CacheTimeControl(storage)
            .validate(ITEM, Remote.EMPTY)
            .toCompletableFuture().join();
    }

    /**
     * Storage holding the item whose metadata carries the given
     * {@code updated-at} (what FileStorage and S3Storage report).
     *
     * @param updated Last-modified instant
     * @return Storage
     */
    private static Storage stamped(final Instant updated) {
        final Storage mem = new InMemoryStorage();
        new BlockingStorage(mem).save(ITEM, "cached".getBytes(StandardCharsets.UTF_8));
        return new Storage.Wrap(mem) {
            @Override
            public CompletableFuture<? extends Meta> metadata(final Key key) {
                return CompletableFuture.completedFuture(
                    new Meta() {
                        @Override
                        public <T> T read(final Meta.ReadOperator<T> opr) {
                            final Map<String, String> raw = new HashMap<>();
                            Meta.OP_UPDATED_AT.put(raw, updated);
                            return opr.take(raw);
                        }
                    }
                );
            }
        };
    }
}
