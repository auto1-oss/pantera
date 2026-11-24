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
package com.auto1.pantera.asto;

import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsSame;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for the default {@link Storage#deleteBatch(Collection)} and for the
 * way {@link SubStorage} translates a paged listing.
 *
 * @since 2.2.14
 */
final class StorageBatchDefaultsTest {

    @Test
    @Timeout(30)
    void defaultDeleteBatchKeepsFewDeletesInFlight() throws Exception {
        final List<CompletableFuture<Void>> pending = new CopyOnWriteArrayList<>();
        final Storage storage = new Bare(
            key -> {
                final CompletableFuture<Void> gate = new CompletableFuture<>();
                pending.add(gate);
                return gate;
            }
        );
        final CompletableFuture<Void> done = storage.deleteBatch(
            IntStream.range(0, 100)
                .mapToObj(idx -> (Key) new Key.From("k", String.valueOf(idx)))
                .collect(Collectors.toList())
        );
        MatcherAssert.assertThat(
            "a batch of a hundred keys starts eight deletes, not a hundred",
            pending.size(), new IsEqual<>(8)
        );
        for (int idx = 0; idx < pending.size(); idx += 1) {
            pending.get(idx).complete(null);
        }
        done.get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "every key is deleted in the end",
            pending.size(), new IsEqual<>(100)
        );
    }

    @Test
    @Timeout(30)
    void defaultDeleteBatchFailsOnFailureThatIsNotAbsence() {
        final PanteraIOException denied = new PanteraIOException("Access denied");
        final Storage storage = new Bare(key -> CompletableFuture.failedFuture(denied));
        final ExecutionException err = Assertions.assertThrows(
            ExecutionException.class,
            () -> storage.deleteBatch(List.of(new Key.From("a"))).get(10, TimeUnit.SECONDS)
        );
        MatcherAssert.assertThat(err.getCause(), new IsSame<>(denied));
    }

    @Test
    @Timeout(30)
    void subStorageTranslatesPrefixTokenAndKeys() throws Exception {
        final List<String> asked = new ArrayList<>(2);
        final Storage origin = new Storage.Wrap(new InMemoryStorage()) {
            @Override
            public CompletableFuture<ListPage> listPage(
                final Key prefix, final Optional<String> after, final int limit
            ) {
                asked.add(String.format("%s|%s|%d", prefix.string(), after.orElse("-"), limit));
                return super.listPage(prefix, after, limit);
            }
        };
        for (final String key : List.of("my/repo/a", "my/repo/b/c", "my/repo/d", "my/repo-x/e")) {
            origin.save(
                new Key.From(key), new Content.From(key.getBytes(StandardCharsets.UTF_8))
            ).join();
        }
        final Storage sub = new SubStorage(new Key.From("my/repo"), origin);
        final ListPage first = sub.listPage(Key.ROOT, Optional.empty(), 2)
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "keys come back relative to the sub storage",
            first.keys(), new IsEqual<>(List.of(new Key.From("a"), new Key.From("b/c")))
        );
        MatcherAssert.assertThat(
            "so does the token",
            first.next(), new IsEqual<>(Optional.of("b/c"))
        );
        final ListPage second = sub.listPage(new Key.From("b"), first.next(), 2)
            .get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the second page is empty: nothing follows b/c inside b",
            second, new IsEqual<>(new ListPage(List.of(), Optional.empty()))
        );
        MatcherAssert.assertThat(
            "the origin is asked with the full prefix and the full token",
            asked, new IsEqual<>(List.of("my/repo|-|2", "my/repo/b|my/repo/b/c|2"))
        );
    }

    /**
     * Storage that implements the abstract operations only, so the defaults
     * of the interface run for the others.
     * @since 2.2.14
     */
    private static final class Bare implements Storage {

        /**
         * Backing storage.
         */
        private final Storage origin;

        /**
         * Delete operation.
         */
        private final Function<Key, CompletableFuture<Void>> deleter;

        /**
         * Ctor.
         * @param deleter Delete operation
         */
        Bare(final Function<Key, CompletableFuture<Void>> deleter) {
            this.origin = new InMemoryStorage();
            this.deleter = deleter;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return this.origin.exists(key);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return this.origin.list(prefix);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            return this.origin.save(key, content);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return this.origin.move(source, destination);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return this.origin.metadata(key);
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return this.origin.value(key);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.deleter.apply(key);
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key, final Function<Storage, CompletionStage<T>> operation
        ) {
            return this.origin.exclusively(key, operation);
        }
    }
}
