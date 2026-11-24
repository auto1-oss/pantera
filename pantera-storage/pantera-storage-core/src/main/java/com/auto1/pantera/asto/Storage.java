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

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.misc.BoundedFanOut;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * The storage.
 * <p>
 * You are supposed to implement this interface the way you want. It has
 * to abstract the binary storage. You may use {@link FileStorage} if you
 * want to work with files. Otherwise, for S3 or something else, you have
 * to implement it yourself.
 */
public interface Storage {

    /**
     * This file exists?
     *
     * @param key The key (file name)
     * @return TRUE if exists, FALSE otherwise
     */
    CompletableFuture<Boolean> exists(Key key);

    /**
     * Return the list of keys that start with this prefix, for
     * example "foo/bar/".
     *
     * <p><strong>Note:</strong> This method recursively lists ALL keys under the prefix.
     * For large directories (100K+ files), use {@link #list(Key, String)} instead
     * for better performance.</p>
     *
     * @param prefix The prefix.
     * @return Collection of relative keys (recursive).
     */
    CompletableFuture<Collection<Key>> list(Key prefix);

    /**
     * List keys hierarchically using a delimiter (non-recursive).
     * 
     * <p>This method returns only immediate children of the prefix, separated into
     * files and directories. This is significantly faster than {@link #list(Key)}
     * for large directories as it avoids recursive traversal.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // List immediate children of "com/" directory
     * ListResult result = storage.list(new Key.From("com/"), "/").join();
     * 
     * // Files: com/README.md, com/LICENSE.txt
     * Collection<Key> files = result.files();
     * 
     * // Directories: com/google/, com/apache/, com/example/
     * Collection<Key> dirs = result.directories();
     * }</pre>
     * 
     * <p><strong>Performance:</strong> For a directory with 1M files in subdirectories,
     * this method returns results in ~100ms vs ~120s for recursive listing.</p>
     * 
     * @param prefix The prefix to list under
     * @param delimiter The delimiter (typically "/") to separate hierarchy levels
     * @return ListResult containing files and directories at this level
     * @since 1.18.19
     */
    default CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
        // Default implementation: fallback to recursive listing and deduplicate
        // Implementations should override this for better performance
        return this.list(prefix).thenApply(
            keys -> {
                final var files = new java.util.ArrayList<Key>();
                final var dirs = new java.util.LinkedHashSet<Key>();
                
                final String prefixStr = prefix.string();
                final int prefixLen = prefixStr.isEmpty() ? 0 : prefixStr.length() + 1;
                
                for (final Key key : keys) {
                    final String keyStr = key.string();
                    if (keyStr.length() <= prefixLen) {
                        continue;
                    }
                    
                    final String relative = keyStr.substring(prefixLen);
                    final int delimIdx = relative.indexOf(delimiter);
                    
                    if (delimIdx < 0) {
                        // File at this level
                        files.add(key);
                    } else {
                        // Directory - extract common prefix
                        final String dirPrefix = keyStr.substring(0, prefixLen + delimIdx + delimiter.length());
                        dirs.add(new Key.From(dirPrefix));
                    }
                }
                
                return new ListResult.Simple(files, new java.util.ArrayList<>(dirs));
            }
        );
    }

    /**
     * Saves the bytes to the specified key.
     *
     * @param key The key
     * @param content Bytes to save
     * @return Completion or error signal.
     */
    CompletableFuture<Void> save(Key key, Content content);

    /**
     * Moves value from one location to another.
     *
     * @param source Source key.
     * @param destination Destination key.
     * @return Completion or error signal.
     */
    CompletableFuture<Void> move(Key source, Key destination);

    /**
     * Get value size.
     *
     * @param key The key of value.
     * @return Size of value in bytes.
     * @deprecated Use {@link #metadata(Key)} to get content size
     */
    @Deprecated
    default CompletableFuture<Long> size(final Key key) {
        return this.metadata(key).thenApply(
            meta -> meta.read(Meta.OP_SIZE).orElseThrow(
                () -> new PanteraException(
                    String.format("SIZE could't be read for %s key", key.string())
                )
            )
        );
    }

    /**
     * Get content metadata.
     * @param key Content key
     * @return Future with metadata
     */
    CompletableFuture<? extends Meta> metadata(Key key);

    /**
     * Obtain bytes by key.
     *
     * @param key The key
     * @return Bytes.
     */
    CompletableFuture<Content> value(Key key);

    /**
     * Removes value from storage. Fails if value does not exist.
     *
     * @param key Key for value to be deleted.
     * @return Completion or error signal.
     */
    CompletableFuture<Void> delete(Key key);

    /**
     * Removes all items with key prefix.
     *
     * @implNote It is important that keys are deleted sequentially.
     * @param prefix Key prefix.
     * @return Completion or error signal.
     */
    default CompletableFuture<Void> deleteAll(final Key prefix) {
        return this.list(prefix).thenCompose(
            keys -> {
                CompletableFuture<Void> res = CompletableFuture.allOf();
                for (final Key key : keys) {
                    res = res.thenCompose(noth -> this.delete(key));
                }
                return res;
            }
        );
    }

    /**
     * Removes the directories left empty in the subtree of {@code prefix}
     * (the prefix directory itself included) and the empty directories above
     * it, up to the storage root. Files are never touched.
     *
     * <p>Only filesystem storages have directories that outlive their files:
     * deleting every key of a repository left its (possibly hidden) working
     * directories behind. Object and in-memory storages have no directories,
     * so the default does nothing.</p>
     *
     * @param prefix Subtree root
     * @return Completion or error signal
     */
    default CompletableFuture<Void> deleteEmptyDirectories(final Key prefix) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * One page of the keys strictly inside {@code prefix}, which is a path
     * prefix: {@code maven} covers {@code maven/a.jar}, never
     * {@code maven-proxy/a.jar} and never a key equal to {@code maven}.
     * {@link Key#ROOT} covers every key.
     *
     * <p>Keys come in one total order, the same on every storage: by the
     * UTF-8 bytes of the key string ({@link Utf8Order}), which is the order
     * of an S3 listing. The page holds the keys strictly greater than
     * {@code after}. The token is the key string the previous page ended at,
     * so it stays valid across restarts and after the keys already returned
     * were deleted; a caller passes back {@link ListPage#next()} unchanged.</p>
     *
     * <p>A page may hold fewer than {@code limit} keys, even none, and still
     * name a next token; the listing is finished only when the token is
     * empty. Keys saved or removed while the listing runs may or may not
     * appear.</p>
     *
     * <p>The storages of real size (file system, S3) never build the whole
     * listing; this default does, through {@link #list(Key)}, and is meant
     * for small and test storages only.</p>
     *
     * @param prefix Path prefix
     * @param after Resume token of the previous page; empty for the first
     * @param limit Maximum number of keys, positive
     * @return Page
     * @since 2.2.14
     */
    default CompletableFuture<ListPage> listPage(
        final Key prefix, final Optional<String> after, final int limit
    ) {
        final CompletableFuture<ListPage> res;
        if (limit < 1) {
            res = CompletableFuture.failedFuture(
                new IllegalArgumentException(
                    String.format("Page limit must be positive: %d", limit)
                )
            );
        } else {
            res = this.list(prefix).thenApply(
                keys -> new PagedKeys(prefix, after, limit).page(keys)
            );
        }
        return res;
    }

    /**
     * Removes the given keys. A key that does not exist counts as removed:
     * repeating a batch, in whole or in part, succeeds.
     *
     * <p>On failure some keys of the batch may be gone and others not. A
     * {@link StorageThrottledException} asks the caller to slow down and
     * repeat the batch.</p>
     *
     * <p>This default deletes key by key, a few at a time; S3 sends
     * multi-object deletes and the file storages remove the directories the
     * batch left empty once per batch.</p>
     *
     * @param keys Keys to remove
     * @return Completion or error signal
     * @since 2.2.14
     */
    default CompletableFuture<Void> deleteBatch(final Collection<Key> keys) {
        return new BoundedFanOut<Key>(
            8,
            key -> this.delete(key).exceptionallyCompose(
                err -> {
                    final CompletableFuture<Void> res;
                    if (new AbsentKey(err).reported()) {
                        res = CompletableFuture.completedFuture(null);
                    } else {
                        res = CompletableFuture.failedFuture(err);
                    }
                    return res;
                }
            )
        ).run(keys);
    }

    /**
     * Aborts the uploads that were started under the path prefix and never
     * finished. Only object storages keep such parts (S3 multipart uploads:
     * invisible to a listing, yet stored and billed); the default has
     * nothing to abort.
     *
     * @param prefix Path prefix, {@link Key#ROOT} for the whole storage
     * @return Number of uploads aborted
     * @since 2.2.14
     */
    default CompletableFuture<Integer> abortIncompleteUploads(final Key prefix) {
        return CompletableFuture.completedFuture(0);
    }

    /**
     * Runs operation exclusively for specified key.
     *
     * @param key Key which is scope of operation.
     * @param operation Operation to be performed exclusively.
     * @param <T> Operation result type.
     * @return Result of operation.
     */
    <T> CompletionStage<T> exclusively(
        Key key,
        Function<Storage, CompletionStage<T>> operation
    );

    /**
     * Get storage identifier. Returned string should allow identifying storage and provide some
     * unique storage information allowing to concrete determine storage instance. For example, for
     * file system storage, it could provide the type and path, for s3 - base url and username.
     * @return String storage identifier
     */
    default String identifier() {
        return getClass().getSimpleName();
    }

    /**
     * Returns the on-disk path of the artifact under {@code key}, when the
     * storage implementation can offer one (e.g. {@link FileStorage}).
     *
     * <p>Callers MUST treat the returned path as <strong>read-only</strong>
     * and MUST NOT delete it. The storage owns lifetime and may evict the
     * file at any time. Callers reading the path concurrently with eviction
     * must handle {@link java.nio.file.NoSuchFileException} gracefully.</p>
     *
     * <p>Default implementation returns {@link Optional#empty()} for
     * storages that don't have a single file path (e.g. blob, memory).</p>
     *
     * @param key Artifact key
     * @return Path if the storage exposes a single file for this key, empty otherwise
     * @since 2.2.0
     */
    default Optional<java.nio.file.Path> pathFor(final Key key) {
        return Optional.empty();
    }

    /**
     * Forwarding decorator for {@link Storage}.
     *
     * @since 0.18
     */
    abstract class Wrap implements Storage {

        /**
         * Delegate storage.
         */
        private final Storage delegate;

        /**
         * @param delegate Delegate storage
         */
        protected Wrap(final Storage delegate) {
            this.delegate = delegate;
        }

        /**
         * Get the underlying delegate storage.
         * Enables wrapper classes to properly close delegate resources.
         * 
         * @return Delegate storage
         * @since 1.0
         */
        protected Storage delegate() {
            return this.delegate;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return this.delegate.exists(key);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return this.delegate.list(prefix);
        }

        @Override
        public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
            return this.delegate.list(prefix, delimiter);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            return this.delegate.save(key, content);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return this.delegate.move(source, destination);
        }

        @Override
        public CompletableFuture<Long> size(final Key key) {
            return this.delegate.size(key);
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return this.delegate.value(key);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.delegate.delete(key);
        }

        @Override
        public CompletableFuture<Void> deleteAll(final Key prefix) {
            return this.delegate.deleteAll(prefix);
        }

        @Override
        public CompletableFuture<Void> deleteEmptyDirectories(final Key prefix) {
            return this.delegate.deleteEmptyDirectories(prefix);
        }

        @Override
        public CompletableFuture<ListPage> listPage(
            final Key prefix, final Optional<String> after, final int limit
        ) {
            return this.delegate.listPage(prefix, after, limit);
        }

        @Override
        public CompletableFuture<Void> deleteBatch(final Collection<Key> keys) {
            return this.delegate.deleteBatch(keys);
        }

        @Override
        public CompletableFuture<Integer> abortIncompleteUploads(final Key prefix) {
            return this.delegate.abortIncompleteUploads(prefix);
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key,
            final Function<Storage, CompletionStage<T>> operation
        ) {
            return this.delegate.exclusively(key, operation);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return this.delegate.metadata(key);
        }

        @Override
        public String identifier() {
            return this.delegate.identifier();
        }

        @Override
        public Optional<java.nio.file.Path> pathFor(final Key key) {
            return this.delegate.pathFor(key);
        }
    }
}
