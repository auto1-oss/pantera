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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import io.reactivex.Flowable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS3.1 regression guard: {@link ProxyCacheWriter#commitStreamed} (Track 4
 * stream-through) and the buffered {@code commit()} path (Track 3,
 * {@code writeWithSidecars}) must save the cache primary by streaming the
 * temp file in bounded chunks, never by materialising the whole artifact on
 * heap via {@code Files.readAllBytes} + a single {@code Content.From(byte[])}.
 *
 * <p>The proof is structural, per CLAUDE.md's "invocation counts, not
 * wall-clock" doctrine: a recording {@link Storage} captures every
 * {@code ByteBuffer} chunk handed to {@code save()}. A whole-body buffer
 * would arrive as exactly ONE chunk containing the entire artifact
 * (that's how {@code Content.From(byte[])} publishes); the streaming
 * implementation must arrive as MULTIPLE chunks, each bounded by the
 * writer's internal chunk size, regardless of artifact size. This
 * deterministically fails against the old {@code readAllBytes} code and
 * passes against the streaming rewrite.
 *
 * @since 2.3.0
 */
final class ProxyCacheWriterStreamingCommitTest {

    /** Larger than the writer's internal 64KB chunk size, to force multiple chunks. */
    private static final int LARGE_SIZE = 5 * 1024 * 1024;

    /** The writer streams in 64KB chunks (see {@code ProxyCacheWriter.CHUNK_SIZE}). */
    private static final int MAX_CHUNK = 64 * 1024;

    @Test
    @DisplayName("commitStreamed (Track 4): primary save() receives many bounded chunks, not one whole-body buffer")
    void streamThroughCommitStreamsPrimaryInBoundedChunks() throws Exception {
        final byte[] payload = randomBytes(LARGE_SIZE);
        final ChunkRecordingStorage cache = new ChunkRecordingStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "stream-test");
        final Key key = new Key.From("com/example/big/1.0/big-1.0.jar");

        final Result<ProxyCacheWriter.StreamedArtifact> result = writer.streamThroughAndCommit(
            key, "https://upstream.example/big-1.0.jar",
            Optional.of((long) payload.length),
            chunkedUpstream(payload, 8192),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(payload))),
            null, null
        ).toCompletableFuture().join();

        final ProxyCacheWriter.StreamedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
        // Drain the tee (the client-facing body) before asserting on the
        // cache-write side, mirroring how the real response path behaves.
        artifact.body().asBytesFuture().join();
        final Result<Void> commit = artifact.verificationOutcome()
            .toCompletableFuture().join();
        assertThat("commit succeeds on sha1 match", commit, instanceOf(Result.Ok.class));

        final List<Integer> chunkSizes = cache.chunkSizesFor(key);
        assertThat(
            "a whole-body buffer would arrive as exactly one chunk; "
                + "streaming must split a " + LARGE_SIZE + "-byte artifact into many",
            chunkSizes.size(), greaterThan(1)
        );
        for (final int size : chunkSizes) {
            assertThat("no single chunk may hold the whole (or a huge part of the) artifact",
                size, lessThanOrEqualTo(MAX_CHUNK));
        }
        assertArrayEquals(payload, cache.bytesFor(key), "cache bytes match upstream exactly");
    }

    @Test
    @DisplayName("commit() (Track 3, writeWithSidecars): primary save() receives many bounded chunks")
    void bufferedCommitStreamsPrimaryInBoundedChunks() throws Exception {
        final byte[] payload = randomBytes(LARGE_SIZE);
        final ChunkRecordingStorage cache = new ChunkRecordingStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final Key key = new Key.From("com/example/big2/1.0/big2-1.0.jar");

        final Result<Void> result = writer.writeWithSidecars(
            key,
            "https://upstream.example/big2-1.0.jar",
            () -> CompletableFuture.completedFuture(
                (InputStream) new ByteArrayInputStream(payload)
            ),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(payload))),
            null
        ).toCompletableFuture().join();

        assertThat("Ok result", result, instanceOf(Result.Ok.class));
        final List<Integer> chunkSizes = cache.chunkSizesFor(key);
        assertThat(
            "a whole-body buffer would arrive as exactly one chunk",
            chunkSizes.size(), greaterThan(1)
        );
        for (final int size : chunkSizes) {
            assertThat("no single chunk may hold the whole artifact",
                size, lessThanOrEqualTo(MAX_CHUNK));
        }
        assertArrayEquals(payload, cache.bytesFor(key), "cache bytes match upstream exactly");
    }

    @Test
    @DisplayName("commitStreamed: sha1 mismatch on a large artifact still rejects + rolls back, no partial state")
    void largeArtifactIntegrityFailureStillRejects() throws Exception {
        final byte[] payload = randomBytes(LARGE_SIZE);
        final ChunkRecordingStorage cache = new ChunkRecordingStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "stream-test");
        final Key key = new Key.From("com/example/corrupt/1.0/corrupt-1.0.jar");
        final String bogusSha1 = "ffffffffffffffffffffffffffffffffffffffff";

        final Result<ProxyCacheWriter.StreamedArtifact> result = writer.streamThroughAndCommit(
            key, "https://upstream.example/corrupt-1.0.jar",
            Optional.of((long) payload.length),
            chunkedUpstream(payload, 8192),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(bogusSha1)),
            null, null
        ).toCompletableFuture().join();

        final ProxyCacheWriter.StreamedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
        // Client still gets the (unverified) bytes -- Track 4 trade-off.
        assertArrayEquals(payload, artifact.body().asBytesFuture().join(), "client received bytes");

        final Result<Void> verify = artifact.verificationOutcome()
            .toCompletableFuture().join();
        assertThat("integrity mismatch rejects the commit", verify, instanceOf(Result.Err.class));
        assertTrue(cache.saved.isEmpty(), "nothing was ever persisted for a rejected write");
    }

    private static byte[] randomBytes(final int size) {
        final byte[] data = new byte[size];
        final java.util.Random rnd = new java.util.Random(42);
        rnd.nextBytes(data);
        return data;
    }

    private static Flowable<ByteBuffer> chunkedUpstream(final byte[] data, final int chunkSize) {
        return Flowable.range(0, (data.length + chunkSize - 1) / chunkSize)
            .map(i -> {
                final int from = i * chunkSize;
                final int to = Math.min(from + chunkSize, data.length);
                return ByteBuffer.wrap(java.util.Arrays.copyOfRange(data, from, to));
            });
    }

    private static Supplier<CompletionStage<Optional<InputStream>>> sidecarServing(final String hex) {
        final byte[] body = hex.getBytes(StandardCharsets.UTF_8);
        return () -> CompletableFuture.completedFuture(
            Optional.<InputStream>of(new ByteArrayInputStream(body))
        );
    }

    private static String sha1Hex(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Storage decorator that records every {@code ByteBuffer} chunk handed
     * to {@code save()} for a given key, then delegates to a real
     * {@link InMemoryStorage} so downstream assertions on the persisted
     * bytes still work.
     */
    private static final class ChunkRecordingStorage implements Storage {
        private final Storage delegate = new InMemoryStorage();
        private final Map<String, List<Integer>> chunkSizes = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<String> saved = new CopyOnWriteArrayList<>();

        List<Integer> chunkSizesFor(final Key key) {
            return this.chunkSizes.getOrDefault(key.string(), List.of());
        }

        byte[] bytesFor(final Key key) {
            return this.delegate.value(key).join().asBytes();
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            this.saved.add(key.string());
            final List<Integer> sizes = new CopyOnWriteArrayList<>();
            this.chunkSizes.put(key.string(), sizes);
            // Tee: record chunk sizes as they flow through, without altering
            // the bytes themselves, then delegate to the real backing store.
            final Content recording = new Content.From(
                content.size(),
                Flowable.fromPublisher(content).doOnNext(buf -> sizes.add(buf.remaining()))
            );
            return this.delegate.save(key, recording);
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
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return this.delegate.move(source, destination);
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
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return this.delegate.metadata(key);
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key, final Function<Storage, CompletionStage<T>> op
        ) {
            return this.delegate.exclusively(key, op);
        }
    }
}
