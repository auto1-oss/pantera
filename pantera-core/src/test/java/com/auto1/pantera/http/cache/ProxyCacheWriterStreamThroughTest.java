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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.fault.Fault;
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
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxyCacheWriter#streamThroughAndCommit} — Track 4
 * stream-through cache write.
 *
 * <p>Pins the Track 4 invariants:
 * <ol>
 *   <li>The teed body emits every upstream byte to the client subscriber in
 *       the same order they arrive — stream-through is byte-faithful.</li>
 *   <li>On a matching upstream {@code .sha1}, the verifying side commit
 *       lands the primary + sidecar in cache (Track 3 sidecar-first order
 *       is exercised but not retested here — see
 *       {@link ProxyCacheWriterCommitOrderTest}).</li>
 *   <li>On a {@code .sha1} mismatch, the cache stays EMPTY even though the
 *       client already received the bytes. This is the explicit Track 4
 *       trade-off: stream-through serves before verifying, but the cache
 *       still upholds the always-verify invariant (Track 3).</li>
 *   <li>On an upstream stream error mid-body, the cache stays empty and the
 *       temp file is cleaned up.</li>
 * </ol>
 *
 * @since 2.2.0
 */
final class ProxyCacheWriterStreamThroughTest {

    private static final Key PRIMARY_KEY =
        new Key.From("com/example/foo/1.0/foo-1.0.jar");

    private static final String UPSTREAM_URI =
        "https://upstream.example/com/example/foo/1.0/foo-1.0.jar";

    private static final byte[] PRIMARY_BYTES =
        "stream-through-test-payload-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("body subscriber receives full upstream payload byte-for-byte")
    void teeEmitsEveryByteToSubscriber() throws Exception {
        final Storage cache = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "stream-test");
        final Result<ProxyCacheWriter.StreamedArtifact> result =
            writer.streamThroughAndCommit(
                PRIMARY_KEY, UPSTREAM_URI,
                Optional.of((long) PRIMARY_BYTES.length),
                chunkedUpstream(PRIMARY_BYTES, 7),
                Map.of(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES))),
                null, null
            ).toCompletableFuture().join();

        assertThat("Ok streamed artifact", result, instanceOf(Result.Ok.class));
        final ProxyCacheWriter.StreamedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();

        final byte[] consumed = artifact.body().asBytesFuture().get(5, TimeUnit.SECONDS);
        assertArrayEquals(PRIMARY_BYTES, consumed, "subscriber sees identical bytes");

        final Result<Void> commit = artifact.verificationOutcome()
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat("commit succeeds on sha1 match", commit, instanceOf(Result.Ok.class));
        assertTrue(cache.exists(PRIMARY_KEY).join(), "primary persisted");
        assertTrue(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "sidecar persisted"
        );
        assertArrayEquals(
            PRIMARY_BYTES,
            cache.value(PRIMARY_KEY).join().asBytes(),
            "cache bytes match upstream"
        );
    }

    @Test
    @DisplayName(".sha1 mismatch: client gets bytes, cache stays empty")
    void integrityMismatchDoesNotPopulateCache() throws Exception {
        final Storage cache = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "stream-test");
        final String bogusSha1 = "ffffffffffffffffffffffffffffffffffffffff";

        final Result<ProxyCacheWriter.StreamedArtifact> result =
            writer.streamThroughAndCommit(
                PRIMARY_KEY, UPSTREAM_URI,
                Optional.of((long) PRIMARY_BYTES.length),
                chunkedUpstream(PRIMARY_BYTES, 9),
                Map.of(ChecksumAlgo.SHA1, sidecarServing(bogusSha1)),
                null, null
            ).toCompletableFuture().join();

        final ProxyCacheWriter.StreamedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
        // Client still receives the full body — stream-through committed
        // the response before verification could fail.
        final byte[] consumed = artifact.body().asBytesFuture().get(5, TimeUnit.SECONDS);
        assertArrayEquals(PRIMARY_BYTES, consumed, "client received unverified bytes");

        final Result<Void> verify = artifact.verificationOutcome()
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat("verification flags integrity failure", verify, instanceOf(Result.Err.class));
        assertThat(
            "fault is UpstreamIntegrity",
            ((Result.Err<Void>) verify).fault(),
            instanceOf(Fault.UpstreamIntegrity.class)
        );
        // But the cache MUST stay empty — always-verify invariant for the cache.
        assertFalse(cache.exists(PRIMARY_KEY).join(), "primary NOT in cache");
        assertFalse(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "sidecar NOT in cache"
        );
    }

    @Test
    @DisplayName("upstream error mid-stream: cache stays empty, verifyDone records failure")
    void upstreamErrorMidStreamLeavesCacheEmpty() throws Exception {
        final Storage cache = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "stream-test");
        // Two chunks then an error — simulates a dropped upstream connection
        // after partial body transfer.
        final Flowable<ByteBuffer> failingUpstream = Flowable
            .just(
                ByteBuffer.wrap("first-".getBytes(StandardCharsets.UTF_8)),
                ByteBuffer.wrap("second".getBytes(StandardCharsets.UTF_8))
            )
            .concatWith(Flowable.error(new java.io.IOException("upstream dropped")));

        final Result<ProxyCacheWriter.StreamedArtifact> result =
            writer.streamThroughAndCommit(
                PRIMARY_KEY, UPSTREAM_URI, Optional.empty(), failingUpstream,
                Map.of(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES))),
                null, null
            ).toCompletableFuture().join();

        final ProxyCacheWriter.StreamedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
        // Subscribing materialises the error to the client.
        final CompletableFuture<byte[]> subscriberAttempt =
            artifact.body().asBytesFuture();
        try {
            subscriberAttempt.get(5, TimeUnit.SECONDS);
        } catch (final Exception expected) {
            // expected — body terminates with onError
        }
        final Result<Void> verify = artifact.verificationOutcome()
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat("verifyDone records failure", verify, instanceOf(Result.Err.class));
        assertFalse(cache.exists(PRIMARY_KEY).join(), "no primary in cache");
        assertFalse(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "no sidecar in cache"
        );
    }

    @Test
    @DisplayName("absent upstream sidecar (404): primary still committed")
    void absentSidecarStillCommitsPrimary() throws Exception {
        final Storage cache = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "stream-test");
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA1,
            () -> CompletableFuture.completedFuture(Optional.<InputStream>empty()));

        final Result<ProxyCacheWriter.StreamedArtifact> result =
            writer.streamThroughAndCommit(
                PRIMARY_KEY, UPSTREAM_URI,
                Optional.of((long) PRIMARY_BYTES.length),
                chunkedUpstream(PRIMARY_BYTES, 5),
                sidecars, null, null
            ).toCompletableFuture().join();

        final ProxyCacheWriter.StreamedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
        artifact.body().asBytesFuture().get(5, TimeUnit.SECONDS);
        final Result<Void> commit = artifact.verificationOutcome()
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat("commit ok when no sidecar to compare", commit, instanceOf(Result.Ok.class));
        assertTrue(cache.exists(PRIMARY_KEY).join(), "primary persisted");
        // Absent sidecar is NOT synthesised.
        assertFalse(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "absent sidecar not invented"
        );
    }

    @Test
    @DisplayName("upstream size carried through to the response body Content")
    void upstreamSizeForwardedToBody() {
        final Storage cache = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "stream-test");
        final Result<ProxyCacheWriter.StreamedArtifact> result =
            writer.streamThroughAndCommit(
                PRIMARY_KEY, UPSTREAM_URI,
                Optional.of((long) PRIMARY_BYTES.length),
                chunkedUpstream(PRIMARY_BYTES, 4),
                Map.of(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES))),
                null, null
            ).toCompletableFuture().join();
        final ProxyCacheWriter.StreamedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.StreamedArtifact>) result).value();
        assertThat(
            "body.size() == upstream Content-Length",
            artifact.body().size(),
            equalTo(Optional.of((long) PRIMARY_BYTES.length))
        );
    }

    /**
     * Chunk a byte array into a back-pressure-friendly Flowable that emits
     * {@code chunkSize}-sized ByteBuffers. The chunks are emitted from a
     * cold Flowable so the tee subscribes lazily, matching the real
     * upstream-body subscription contract.
     */
    private static Flowable<ByteBuffer> chunkedUpstream(final byte[] data, final int chunkSize) {
        return Flowable.range(0, (data.length + chunkSize - 1) / chunkSize)
            .map(i -> {
                final int from = i * chunkSize;
                final int to = Math.min(from + chunkSize, data.length);
                final byte[] slice = new byte[to - from];
                System.arraycopy(data, from, slice, 0, to - from);
                return ByteBuffer.wrap(slice);
            });
    }

    private static Supplier<CompletionStage<Optional<InputStream>>> sidecarServing(
        final String hex
    ) {
        final byte[] body = hex.getBytes(StandardCharsets.UTF_8);
        return () -> CompletableFuture.completedFuture(
            Optional.<InputStream>of(new ByteArrayInputStream(body))
        );
    }

    private static String sha1Hex(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(bytes)
            );
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
