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
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.asto.MetaCommon;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.error.InvalidDigestException;
import com.auto1.pantera.docker.misc.DigestedFlowable;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.slice.ContentWithSize;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blob upload.
 * See <a href="https://docs.docker.com/registry/spec/api/#blob-upload">Blob Upload</a>
 */
public final class Upload {

    /**
     * Name of an upload part: start offset, length, digest algorithm, hex.
     */
    private static final Pattern PART_NAME =
        Pattern.compile("part_(\\d{19})_(\\d{19})_([a-z0-9]+)_([a-f0-9]+)");

    /**
     * Prefix of temporary keys inside the upload root.
     */
    private static final String TMP = "tmp_";

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
     * Appends a chunk of data to upload, after whatever was uploaded so far.
     *
     * @param chunk Chunk of data.
     * @return Offset of the last byte uploaded after appending chunk.
     */
    public CompletableFuture<Long> append(final Content chunk) {
        return this.append(chunk, Optional.empty());
    }

    /**
     * Appends a chunk of data to upload. Chunks are stored as ordered parts
     * and concatenated when the upload is committed, so an upload may take
     * any number of PATCH requests (distribution spec "pushing a blob in
     * chunks").
     *
     * @param chunk Chunk of data.
     * @param start Declared start offset of the chunk ({@code Content-Range}),
     *  empty when the client did not declare one. A declared start that is
     *  not the current end of the upload fails with
     *  {@link UploadRangeException} and stores nothing.
     * @return Offset of the last byte uploaded after appending chunk.
     */
    public CompletableFuture<Long> append(final Content chunk, final Optional<Long> start) {
        return this.parts().thenCompose(
            parts -> {
                final long offset = Upload.total(parts);
                if (start.isPresent() && start.get() != offset) {
                    return chunk.discard().thenCompose(
                        ignored -> CompletableFuture.<Long>failedFuture(
                            new UploadRangeException(offset)
                        )
                    );
                }
                final Key tmp = this.temporary();
                final DigestedFlowable data = new DigestedFlowable(chunk);
                return this.storage.save(tmp, new Content.From(chunk.size(), data))
                    .thenCompose(nothing -> this.storage.metadata(tmp))
                    .thenApply(meta -> new MetaCommon(meta).size())
                    .thenCompose(
                        size -> this.storage.move(
                            tmp, this.part(new Part(offset, size, data.digest()))
                        ).thenApply(ignored -> offset + size - 1)
                    );
            }
        );
    }

    /**
     * Get offset for the uploaded content.
     *
     * @return Offset of the last uploaded byte, 0 when nothing was uploaded.
     */
    public CompletableFuture<Long> offset() {
        return this.parts().thenApply(parts -> Math.max(Upload.total(parts) - 1, 0));
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
        return this.parts().thenCompose(
            parts -> {
                final List<Part> data = parts.stream()
                    .filter(part -> part.length() > 0).toList();
                final CompletableFuture<Key> source;
                if (data.size() > 1) {
                    source = this.concatenate(data, digest);
                } else {
                    // Single chunk (or an empty blob): the part is already
                    // named by its digest, so it moves into place as is.
                    source = (data.isEmpty() ? parts : data).stream().findFirst()
                        .filter(part -> part.matches(digest))
                        .map(part -> CompletableFuture.completedFuture(this.part(part)))
                        .orElseGet(
                            () -> CompletableFuture.failedFuture(
                                new InvalidDigestException(digest.toString())
                            )
                        );
                }
                return source.thenCompose(key -> this.moveTo(layers, digest, key));
            }
        );
    }

    /**
     * Commit the upload, first appending the PUT body when it carries data:
     * the final PUT may hold the last chunk, or the whole blob for a
     * monolithic upload.
     *
     * @param layers Target layers.
     * @param digest Expected blob digest.
     * @param body PUT request body.
     * @param headers PUT request headers.
     * @return Completion.
     */
    public CompletableFuture<Void> putTo(
        final Layers layers,
        final Digest digest,
        final Content body,
        final Headers headers
    ) {
        final ContentWithSize sized = new ContentWithSize(body, headers);
        final CompletableFuture<Void> stage;
        if (body == Content.EMPTY || sized.size().filter(size -> size == 0L).isPresent()) {
            stage = body.discard();
        } else {
            stage = this.append(sized).thenApply(ignored -> null);
        }
        return stage.thenCompose(ignored -> this.putTo(layers, digest));
    }

    /**
     * Move a verified upload key into the layers and clean the upload up.
     *
     * @param layers Target layers
     * @param digest Blob digest
     * @param source Key holding exactly the blob bytes
     * @return Completion
     */
    private CompletableFuture<Void> moveTo(
        final Layers layers, final Digest digest, final Key source
    ) {
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
        ).thenCompose(blob -> this.delete());
    }

    /**
     * Concatenate the parts in offset order into one temporary key and
     * verify the result against the expected digest.
     *
     * @param parts Non-empty parts, ordered by offset
     * @param digest Expected digest
     * @return Key holding the verified concatenation
     */
    private CompletableFuture<Key> concatenate(final List<Part> parts, final Digest digest) {
        final Key joined = this.temporary();
        final DigestedFlowable data = new DigestedFlowable(
            Flowable.fromIterable(parts).concatMap(
                part -> Flowable.fromPublisher(this.lazyValue(this.part(part)))
            )
        );
        return this.storage.save(joined, new Content.From(Upload.total(parts), data))
            .thenCompose(
                nothing -> {
                    if (data.digest().string().equals(digest.string())) {
                        return CompletableFuture.completedFuture(joined);
                    }
                    return this.storage.delete(joined).thenCompose(
                        ignored -> CompletableFuture.<Key>failedFuture(
                            new InvalidDigestException(digest.toString())
                        )
                    );
                }
            );
    }

    /**
     * Publisher that opens a stored value only when subscribed, so the
     * parts of a concatenation are read one after another.
     *
     * @param key Key to read
     * @return Publisher of the value bytes
     */
    private Publisher<ByteBuffer> lazyValue(final Key key) {
        return subscriber -> this.storage.value(key).whenComplete(
            (content, err) -> {
                if (err == null) {
                    content.subscribe(subscriber);
                } else {
                    Flowable.<ByteBuffer>error(err).subscribe(subscriber);
                }
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
     * Temporary key inside the upload root.
     *
     * @return Key
     */
    private Key temporary() {
        return new Key.From(this.root(), Upload.TMP + UUID.randomUUID());
    }

    /**
     * Storage key of an upload part.
     *
     * @param part Part
     * @return Key
     */
    private Key part(final Part part) {
        return new Key.From(this.root(), part.name());
    }

    /**
     * List the upload parts ordered by offset.
     *
     * @return Parts
     */
    private CompletableFuture<List<Part>> parts() {
        return this.storage.list(this.root())
            .thenApply(
                keys -> keys.stream()
                    .map(key -> Part.parse(new KeyLastPart(key).get()))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingLong(Part::start))
                    .toList()
            );
    }

    /**
     * Total number of bytes in the parts.
     *
     * @param parts Parts
     * @return Byte count
     */
    private static long total(final List<Part> parts) {
        return parts.stream().mapToLong(Part::length).sum();
    }

    /**
     * One uploaded chunk. Its storage name carries the start offset
     * (zero-padded, so names sort by offset), the length and the digest,
     * so the upload state never needs a metadata read per part.
     *
     * @param start Offset of the first byte
     * @param length Byte count
     * @param digest Chunk digest
     */
    private record Part(long start, long length, Digest digest) {

        /**
         * Parse a part from its storage name.
         *
         * @param name Last key segment
         * @return Part, empty for keys that are not parts
         */
        static Optional<Part> parse(final String name) {
            final Matcher matcher = Upload.PART_NAME.matcher(name);
            final Optional<Part> part;
            if (matcher.matches()) {
                part = Optional.of(
                    new Part(
                        Long.parseLong(matcher.group(1)),
                        Long.parseLong(matcher.group(2)),
                        new Digest.FromString(matcher.group(3) + ':' + matcher.group(4))
                    )
                );
            } else {
                part = Optional.empty();
            }
            return part;
        }

        /**
         * Storage name of the part.
         *
         * @return Name
         */
        String name() {
            return String.format(
                "part_%019d_%019d_%s_%s", this.start, this.length,
                this.digest.alg(), this.digest.hex()
            );
        }

        /**
         * Whether this part's bytes have the digest.
         *
         * @param other Digest
         * @return True on match
         */
        boolean matches(final Digest other) {
            return this.digest.string().equals(other.string());
        }
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
