/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.MetaCommon;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.error.InvalidDigestException;
import com.auto1.pantera.docker.misc.DigestedFlowable;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.slice.ContentWithSize;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Blob upload.
 * See <a href="https://docs.docker.com/registry/spec/api/#blob-upload">Blob Upload</a>
 */
public final class Upload {

    private final Storage storage;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Upload UUID.
     */
    private final String uuid;

    /**
     * @param storage Storage.
     * @param name Repository name.
     * @param uuid Upload UUID.
     */
    public Upload(Storage storage, String name, String uuid) {
        this.storage = storage;
        this.name = name;
        this.uuid = uuid;
    }

    /**
     * Read UUID.
     *
     * @return UUID.
     */
    public String uuid() {
        return this.uuid;
    }

    /**
     * Start upload with {@code Instant.now()} upload start time.
     *
     * @return Completion or error signal.
     */
    public CompletableFuture<Void> start() {
        return this.start(Instant.now());
    }

    /**
     * Start upload.
     *
     * @param time Upload start time
     * @return Future
     */
    public CompletableFuture<Void> start(Instant time) {
        return this.storage.save(
            this.started(),
            new Content.From(time.toString().getBytes(StandardCharsets.UTF_8))
        );
    }

    /**
     * Cancel upload.
     *
     * @return Completion or error signal.
     */
    public CompletableFuture<Void> cancel() {
        final Key key = this.started();
        return this.storage
            .exists(key)
            .thenCompose(found -> this.storage.delete(key));
    }

    /**
     * Appends a chunk of data to upload.
     *
     * @param chunk Chunk of data.
     * @return Offset after appending chunk.
     */
    public CompletableFuture<Long> append(final Content chunk) {
        return this.chunks().thenCompose(
            chunks -> {
                if (!chunks.isEmpty()) {
                    throw new UnsupportedOperationException("Multiple chunks are not supported");
                }
                final Key tmp = new Key.From(this.root(), UUID.randomUUID().toString());
                final DigestedFlowable data = new DigestedFlowable(chunk);
                return this.storage.save(tmp, new Content.From(chunk.size(), data)).thenCompose(
                    nothing -> {
                        final Key key = this.chunk(data.digest());
                        return this.storage.move(tmp, key).thenApply(ignored -> key);
                    }
                ).thenCompose(
                    key -> this.storage.metadata(key)
                        .thenApply(meta -> new MetaCommon(meta).size())
                        .thenApply(updated -> updated - 1)
                );
            }
        );
    }

    /**
     * Get offset for the uploaded content.
     *
     * @return Offset.
     */
    public CompletableFuture<Long> offset() {
        return this.chunks().thenCompose(
            chunks -> {
                final CompletionStage<Long> result;
                if (chunks.isEmpty()) {
                    result = CompletableFuture.completedFuture(0L);
                } else {
                    final Key key = chunks.iterator().next();
                    result = this.storage.metadata(key)
                        .thenApply(meta -> new MetaCommon(meta).size())
                        .thenApply(size -> Math.max(size - 1, 0));
                }
                return result;
            }
        );
    }

    /**
     * Puts uploaded data to {@link Layers} creating a {@link Blob} with specified {@link Digest}.
     * If upload data mismatch provided digest then error occurs and operation does not complete.
     *
     * @param layers Target layers.
     * @param digest Expected blob digest.
     * @return Created blob.
     */
    public CompletableFuture<Void> putTo(final Layers layers, final Digest digest) {
        final Key source = this.chunk(digest);
        return this.storage.exists(source)
            .thenCompose(
                exists -> {
                    if (exists) {
                        return layers.put(
                            new BlobSource() {
                                @Override
                                public Digest digest() {
                                    return digest;
                                }

                                @Override
                                public CompletableFuture<Void> saveTo(Storage asto, Key key) {
                                    return asto.move(source, key);
                                }
                            }
                        ).thenCompose(
                            blob -> this.delete()
                        );
                    }
                    return CompletableFuture.failedFuture(new InvalidDigestException(digest.toString()));
                }
            );
    }

    public CompletableFuture<Void> putTo(
        final Layers layers,
        final Digest digest,
        final Content body,
        final Headers headers
    ) {
        return this.chunks().thenCompose(
            chunks -> {
                final CompletableFuture<Void> stage;
                if (chunks.isEmpty() && body != Content.EMPTY) {
                    final ContentWithSize sized = new ContentWithSize(body, headers);
                    stage = this.append(sized).thenApply(ignored -> null);
                } else {
                    stage = CompletableFuture.completedFuture(null);
                }
                return stage.thenCompose(ignored -> this.putTo(layers, digest));
            }
        );
    }

    /**
     * Root key for upload chunks.
     *
     * @return Root key.
     */
    Key root() {
        return Layout.upload(this.name, this.uuid);
    }

    /**
     * Upload started marker key.
     *
     * @return Key.
     */
    private Key started() {
        return new Key.From(this.root(), "started");
    }

    /**
     * Build upload chunk key for given digest.
     *
     * @param digest Digest.
     * @return Chunk key.
     */
    private Key chunk(final Digest digest) {
        return new Key.From(this.root(), digest.alg() + '_' + digest.hex());
    }

    /**
     * List all chunk keys.
     *
     * @return Chunk keys.
     */
    private CompletableFuture<Collection<Key>> chunks() {
        return this.storage.list(this.root())
            .thenApply(
                keys -> keys.stream()
                    .filter(
                        key -> {
                            final String value = key.string();
                            return !value.equals("started") && !value.endsWith("/started");
                        }
                    )
                    .toList()
            );
    }

    /**
     * Deletes upload blob data.
     *
     * @return Completion or error signal.
     */
    private CompletionStage<Void> delete() {
        return this.storage.list(this.root())
            .thenCompose(
                list -> CompletableFuture.allOf(
                    list.stream()
                        .map(this.storage::delete)
                        .toArray(CompletableFuture[]::new)
                )
            );
    }
}
