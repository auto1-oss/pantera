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

import com.auto1.pantera.asto.ext.CompletableFutureSupport;
import com.auto1.pantera.asto.lock.storage.StorageLock;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Sub storage is a storage in storage.
 * <p>
 * It decorates origin storage and proxies all calls by appending prefix key.
 * </p>
 * @since 0.21
 */
public final class SubStorage implements Storage {

    /**
     * Prefix.
     */
    private final Key prefix;

    /**
     * Origin storage.
     */
    private final Storage origin;

    /**
     * Storage string identifier.
     */
    private final String id;

    /**
     * Pre-compiled pattern used by {@link #list(Key)} and
     * {@link #list(Key, String)} to strip the configured prefix from
     * returned keys. Hoisted out of the hot path to avoid re-compiling
     * on every call.
     */
    private final Pattern listPattern;

    /**
     * Sub storage with prefix.
     * @param prefix Prefix key
     * @param origin Origin key
     */
    public SubStorage(final Key prefix, final Storage origin) {
        this.prefix = prefix;
        this.origin = origin;
        this.id = String.format(
            "SubStorage: prefix=%s, origin=%s", this.prefix, this.origin.identifier()
        );
        this.listPattern = Pattern.compile(
            "^" + Pattern.quote(this.prefix.string()) + "/"
        );
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        return this.origin.exists(new PrefixedKed(this.prefix, key));
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key filter) {
        return this.origin.list(new PrefixedKed(this.prefix, filter)).thenApply(
            keys -> keys.stream()
                .map(key -> new Key.From(this.listPattern.matcher(key.string()).replaceFirst("")))
                .collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<ListResult> list(final Key root, final String delimiter) {
        return this.origin
            .list(new PrefixedKed(this.prefix, root), delimiter)
            .thenApply(result -> {
                final Collection<Key> files = result.files().stream()
                    .map(key -> new Key.From(this.listPattern.matcher(key.string()).replaceFirst("")))
                    .collect(Collectors.toList());
                final Collection<Key> dirs = result.directories().stream()
                    .map(key -> new Key.From(this.listPattern.matcher(key.string()).replaceFirst("")))
                    .collect(Collectors.toList());
                return new ListResult.Simple(files, dirs);
            });
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final CompletableFuture<Void> res;
        if (Key.ROOT.equals(key)) {
            res = new CompletableFutureSupport.Failed<Void>(
                new PanteraIOException("Unable to save to root")
            ).get();
        } else {
            res = this.origin.save(new PrefixedKed(this.prefix, key), content);
        }
        return res;
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return this.origin.move(
            new PrefixedKed(this.prefix, source),
            new PrefixedKed(this.prefix, destination)
        );
    }

    @Override
    @Deprecated
    public CompletableFuture<Long> size(final Key key) {
        return this.origin.size(new PrefixedKed(this.prefix, key));
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return this.origin.metadata(new PrefixedKed(this.prefix, key));
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        return this.origin.value(new PrefixedKed(this.prefix, key));
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return this.origin.delete(new PrefixedKed(this.prefix, key));
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key key,
        final Function<Storage, CompletionStage<T>> operation
    ) {
        return new UnderLockOperation<>(new StorageLock(this, key), operation).perform(this);
    }

    @Override
    public String identifier() {
        return this.id;
    }

    /**
     * Key with prefix.
     * @since 0.21
     */
    public static final class PrefixedKed extends Key.Wrap {

        /**
         * Key with prefix.
         * @param prefix Prefix key
         * @param key Key
         */
        public PrefixedKed(final Key prefix, final Key key) {
            super(new Key.From(prefix, key));
        }
    }
}
