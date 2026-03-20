/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.fs.FileStorage;
import java.util.Collection;
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
    }
}
