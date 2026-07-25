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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.MetaCommon;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.rx.RxFuture;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.error.InvalidDigestException;
import com.auto1.pantera.docker.error.NonContiguousChunkException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.slice.ContentWithSize;
import io.reactivex.Flowable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Blob upload.
 * See <a href="https://docs.docker.com/registry/spec/api/#blob-upload">Blob Upload</a>
 *
 * <p>Chunks accumulate under a dedicated {@code chunks/} subtree, one key
 * per chunk named by its (zero-padded, so lexicographic order == numeric
 * order) starting byte offset — the offset itself, not a content digest,
 * because the digest that matters is the one over the FINAL assembled
 * blob, computed once at {@link #putTo(Layers, Digest)} time (WS4-docker.9's
 * explicit-verify primitive, reused here). A separate small {@code offset}
 * marker file tracks the running byte count so {@link #offset()} and the
 * contiguity check in {@link #append(Content, Optional)} are O(1) reads
 * rather than a full re-scan/re-sum of every chunk on each call.
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
     * Appends a chunk of data to upload, with no {@code Content-Range}
     * contiguity claim to validate (legacy/monolithic callers).
     *
     * @param chunk Chunk of data.
     * @return Offset (0-based index of the last received byte) after
     *         appending.
     */
    public CompletableFuture<Long> append(final Content chunk) {
        return this.append(chunk, Optional.empty());
    }

    /**
     * Appends a chunk of data to upload. Supports an arbitrary number of
     * sequential chunks (WS4-docker.6) — each is staged under a key named
     * by its starting byte offset, so {@link #putTo(Layers, Digest)} can
     * later assemble them back in order.
     *
     * @param chunk Chunk of data.
     * @param declaredStart Start byte offset the client claimed via {@code
     *                       Content-Range}, if any. When present and it does
     *                       not match the number of bytes already received,
     *                       the chunk is rejected with {@link
     *                       NonContiguousChunkException} (416) rather than
     *                       silently accepted out of order.
     * @return Offset (0-based index of the last received byte) after
     *         appending.
     */
    public CompletableFuture<Long> append(final Content chunk, final Optional<Long> declaredStart) {
        return this.receivedBytes().thenCompose(
            received -> {
                if (declaredStart.isPresent() && !declaredStart.get().equals(received)) {
                    return CompletableFuture.<Long>failedFuture(
                        new NonContiguousChunkException(received, declaredStart.get())
                    );
                }
                final Key tmp = new Key.From(this.root(), UUID.randomUUID().toString());
                return this.storage.save(tmp, chunk).thenCompose(
                    nothing -> {
                        final Key key = this.chunkKey(received);
                        return this.storage.move(tmp, key).thenApply(ignored -> key);
                    }
                ).thenCompose(
                    key -> this.storage.metadata(key).thenApply(meta -> new MetaCommon(meta).size())
                ).thenCompose(
                    size -> this.setReceivedBytes(received + size)
                        .thenApply(ignored -> Math.max(received + size - 1, 0))
                );
            }
        );
    }

    /**
     * Get offset for the uploaded content.
     *
     * @return Offset (0-based index of the last received byte), or
     *         {@code 0} if nothing has been received yet.
     */
    public CompletableFuture<Long> offset() {
        return this.receivedBytes().thenApply(received -> Math.max(received - 1, 0));
    }

    /**
     * Puts uploaded data to {@link Layers} creating a {@link Blob} with specified {@link Digest}.
     * Assembles every staged chunk, in the order they were appended, into a single stream and
     * verifies it against the client-claimed {@code digest} via {@link CheckedBlobSource} — the
     * same digest-verifying primitive {@code CachingBlob} uses for the proxy cache-store
     * (WS4-docker.1) — so a mismatch fails explicitly with {@link InvalidDigestException}
     * carrying both the actually-computed and claimed digests, rather than an opaque
     * chunk-key lookup miss. The stored/served digest is therefore always the computed one.
     *
     * @param layers Target layers.
     * @param digest Client-claimed blob digest.
     * @return Created blob.
     */
    public CompletableFuture<Void> putTo(final Layers layers, final Digest digest) {
        return this.orderedChunkKeys().thenCompose(
            keys -> {
                if (keys.isEmpty()) {
                    return CompletableFuture.failedFuture(
                        new InvalidDigestException(
                            String.format("no uploaded data found for digest: %s", digest)
                        )
                    );
                }
                return this.receivedBytes().thenCompose(
                    size -> layers.put(
                        new CheckedBlobSource(
                            new Content.From(size, this.assembled(keys)),
                            digest
                        )
                    ).thenCompose(ignored -> this.delete())
                );
            }
        );
    }

    public CompletableFuture<Void> putTo(
        final Layers layers,
        final Digest digest,
        final Content body,
        final Headers headers
    ) {
        return this.orderedChunkKeys().thenCompose(
            keys -> {
                final CompletableFuture<Void> stage;
                if (keys.isEmpty() && body != Content.EMPTY) {
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
     * Root under which every chunk is staged, one key per chunk.
     *
     * @return Chunks root key.
     */
    private Key chunksRoot() {
        return new Key.From(this.root(), "chunks");
    }

    /**
     * Build a chunk's staging key from its starting byte offset. Zero-padded
     * to a fixed width so that listing {@link #chunksRoot()} and sorting the
     * resulting keys lexicographically yields append order, regardless of
     * the backing storage's own listing order.
     *
     * @param start Starting byte offset of this chunk within the final blob.
     * @return Chunk key.
     */
    private Key chunkKey(final long start) {
        return new Key.From(this.chunksRoot(), String.format("%020d", start));
    }

    /**
     * Marker key holding the running total byte count received so far, as
     * a decimal string.
     *
     * @return Offset-marker key.
     */
    private Key offsetMarker() {
        return new Key.From(this.root(), "offset");
    }

    /**
     * Total bytes received so far across every appended chunk.
     *
     * @return Byte count, {@code 0} if no chunk has been appended yet.
     */
    private CompletableFuture<Long> receivedBytes() {
        final Key marker = this.offsetMarker();
        return this.storage.exists(marker).thenCompose(
            exists -> exists
                ? this.storage.value(marker).thenCompose(Content::asStringFuture).thenApply(Long::parseLong)
                : CompletableFuture.completedFuture(0L)
        );
    }

    /**
     * Records the new running total byte count after a successful append.
     *
     * @param total New cumulative byte count.
     * @return Completion signal.
     */
    private CompletableFuture<Void> setReceivedBytes(final long total) {
        return this.storage.save(
            this.offsetMarker(),
            new Content.From(Long.toString(total).getBytes(StandardCharsets.US_ASCII))
        );
    }

    /**
     * Lists every staged chunk key, sorted by starting byte offset (append order).
     *
     * @return Ordered chunk keys.
     */
    private CompletableFuture<List<Key>> orderedChunkKeys() {
        return this.storage.list(this.chunksRoot()).thenApply(
            keys -> keys.stream().sorted(Comparator.comparing(Key::string)).toList()
        );
    }

    /**
     * Builds a single, non-blocking byte stream that concatenates every
     * chunk's stored bytes in order. Each chunk is fetched from storage
     * only as the previous one finishes streaming ({@code concatMap}), so
     * memory use stays bounded to one chunk at a time regardless of how
     * many chunks (or how large the assembled blob) there are.
     *
     * @param keys Ordered chunk keys (see {@link #orderedChunkKeys()}).
     * @return Concatenated byte stream.
     */
    private Flowable<ByteBuffer> assembled(final List<Key> keys) {
        return Flowable.fromIterable(keys).concatMap(
            key -> RxFuture.single(this.storage.value(key)).flatMapPublisher(Flowable::fromPublisher)
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
