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
package com.auto1.pantera.settings;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A storage in which the directory of a renamed repository is addressed by
 * the repository's name.
 *
 * <p>A repository's data lives under its immutable key, which stops being
 * equal to its name once it is renamed. The management API addresses a
 * repository's files as {@code <name>/<path>} on the repository's raw
 * storage; this view maps the leading {@code <name>} to the key going in and
 * the key back to the name coming out, so that code keeps working for a
 * renamed repository without knowing about keys. Keys outside
 * {@code <name>/} are passed through untouched.</p>
 *
 * @since 2.2.14
 */
public final class RekeyedStorage extends Storage.Wrap {

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Repository key.
     */
    private final String key;

    /**
     * Ctor.
     * @param origin Raw storage of the repository
     * @param name Repository name
     * @param key Repository key
     */
    public RekeyedStorage(final Storage origin, final String name, final String key) {
        super(origin);
        this.name = name;
        this.key = key;
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key path) {
        return super.exists(this.inward(path));
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key prefix) {
        return super.list(this.inward(prefix)).thenApply(this::outward);
    }

    @Override
    public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
        return super.list(this.inward(prefix), delimiter).thenApply(
            result -> new ListResult.Simple(
                this.outward(result.files()), this.outward(result.directories())
            )
        );
    }

    @Override
    public CompletableFuture<Void> save(final Key path, final Content content) {
        return super.save(this.inward(path), content);
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return super.move(this.inward(source), this.inward(destination));
    }

    @Override
    public CompletableFuture<Long> size(final Key path) {
        return super.size(this.inward(path));
    }

    @Override
    public CompletableFuture<Content> value(final Key path) {
        return super.value(this.inward(path));
    }

    @Override
    public CompletableFuture<Void> delete(final Key path) {
        return super.delete(this.inward(path));
    }

    @Override
    public CompletableFuture<Void> deleteAll(final Key prefix) {
        return super.deleteAll(this.inward(prefix));
    }

    @Override
    public CompletableFuture<Void> deleteEmptyDirectories(final Key prefix) {
        return super.deleteEmptyDirectories(this.inward(prefix));
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key path, final Function<Storage, CompletionStage<T>> operation
    ) {
        return super.exclusively(this.inward(path), ignored -> operation.apply(this));
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key path) {
        return super.metadata(this.inward(path));
    }

    @Override
    public CompletableFuture<ListPage> listPage(
        final Key prefix, final Optional<String> after, final int limit
    ) {
        return super.listPage(
            this.inward(prefix),
            after.map(pos -> RekeyedStorage.swap(new Key.From(pos), this.name, this.key).string()),
            limit
        ).thenApply(
            page -> new ListPage(
                List.copyOf(this.outward(page.keys())),
                page.next().map(
                    pos -> RekeyedStorage.swap(new Key.From(pos), this.key, this.name).string()
                )
            )
        );
    }

    @Override
    public CompletableFuture<Void> deleteBatch(final Collection<Key> keys) {
        return super.deleteBatch(
            keys.stream().map(this::inward).collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Integer> abortIncompleteUploads(final Key prefix) {
        return super.abortIncompleteUploads(this.inward(prefix));
    }

    @Override
    public Optional<Path> pathFor(final Key path) {
        return super.pathFor(this.inward(path));
    }

    /**
     * Map a name-addressed key to the key-addressed one.
     * @param path Key as the caller wrote it
     * @return Key in the raw storage
     */
    private Key inward(final Key path) {
        return RekeyedStorage.swap(path, this.name, this.key);
    }

    /**
     * Map listed keys back to name-addressed ones.
     * @param keys Keys of the raw storage
     * @return Keys as the caller expects them
     */
    private Collection<Key> outward(final Collection<Key> keys) {
        return keys.stream()
            .map(item -> RekeyedStorage.swap(item, this.key, this.name))
            .collect(Collectors.toList());
    }

    /**
     * Replace the leading directory of a key.
     * @param path Key
     * @param from Leading directory to replace
     * @param into Replacement
     * @return Key with the leading directory replaced, the same key when it
     *  is not inside {@code from}
     */
    private static Key swap(final Key path, final String from, final String into) {
        final String text = path.string();
        final Key res;
        if (text.equals(from)) {
            res = new Key.From(into);
        } else if (text.startsWith(from + "/")) {
            res = new Key.From(into + text.substring(from.length()));
        } else {
            res = path;
        }
        return res;
    }
}
