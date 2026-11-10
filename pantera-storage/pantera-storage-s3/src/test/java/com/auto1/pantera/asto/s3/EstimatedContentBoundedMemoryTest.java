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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Content;
import io.reactivex.Flowable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS3.1 regression guard for {@link EstimatedContentCompliment}: the
 * size-unknown-upload path used to spool the WHOLE body to a temp file (via
 * {@code new File(temp).write(this.original)}) regardless of {@code limit},
 * then re-read it just to decide single-part vs multipart. The rewrite must
 * buffer only up to the relevant threshold and stream the rest.
 *
 * <p>Per CLAUDE.md's "invocation counts, not wall-clock" doctrine, the
 * boundedness proof is structural: a counting fake {@link Publisher} tracks
 * exactly how many bytes were pulled from the upstream BEFORE the returned
 * {@link Content} is even subscribed to. A whole-body spool would pull
 * every byte during that phase; the streaming rewrite must pull only up to
 * (approximately) the configured limit.
 *
 * @since 2.3.0
 */
final class EstimatedContentBoundedMemoryTest {

    /** Larger than any threshold exercised below, to prove genuine boundedness. */
    private static final int LARGE_SIZE = 20 * 1024 * 1024;

    @Test
    @DisplayName("multipart-allowed (2-arg ctor): only ~limit bytes are pulled before the Content resolves")
    void unknownSizeProbeBoundsUpstreamPullBeforeDeciding() throws Exception {
        final byte[] data = randomBytes(LARGE_SIZE);
        final long limit = 1024 * 1024;
        final CountingChunkedPublisher source = new CountingChunkedPublisher(data, 8192);
        final Content content = new EstimatedContentCompliment(
            new Content.From(Optional.empty(), source), limit
        ).estimate().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat("unknown size once the limit is crossed", content.size(), new IsEqual<>(Optional.empty()));
        assertThat(
            "a whole-body spool would have pulled all " + LARGE_SIZE + " bytes already; "
                + "the streaming probe must stop at roughly the " + limit + "-byte limit",
            source.emittedBytes(), lessThan(LARGE_SIZE)
        );
        assertThat(
            "the probe phase must not have pulled drastically more than the limit",
            source.emittedBytes(), lessThan((int) limit + 8192 * 2)
        );

        // Draining the resulting Content must still yield every byte,
        // proving the live pass-through delivers the untouched remainder.
        final byte[] consumed = content.asBytesFuture().get(5, TimeUnit.SECONDS);
        assertArrayEquals(data, consumed, "full content byte-identical after streaming the remainder");
        assertThat("upstream fully drained once the real subscriber consumes it",
            source.emittedBytes(), equalTo(LARGE_SIZE));
    }

    @Test
    @DisplayName("multipart-allowed (2-arg ctor): completing within the limit resolves a known size, no live bridge needed")
    void unknownSizeProbeResolvesKnownSizeWhenSmallerThanLimit() throws Exception {
        final byte[] data = randomBytes(4096);
        final Content content = new EstimatedContentCompliment(
            new Content.From(Optional.empty(), Flowable.just(ByteBuffer.wrap(data))),
            1024 * 1024
        ).estimate().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat("size known when upstream completes before the limit",
            content.size(), new IsEqual<>(Optional.of((long) data.length)));
        assertArrayEquals(data, content.asBytesFuture().get(5, TimeUnit.SECONDS), "bytes preserved");
    }

    @Test
    @DisplayName("multipart-disabled (1-arg ctor): a payload over the internal memory cap spills only the excess to disk")
    void knownSizeProbeSpillsOverflowToDiskForLargePayload() throws Exception {
        final byte[] data = randomBytes(LARGE_SIZE);
        final int overflowFilesBefore = countOverflowFiles();

        final Content content = new EstimatedContentCompliment(
            new Content.From(Optional.empty(), chunked(data, 64 * 1024))
        ).estimate().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat("size must be known -- putObject (no multipart) needs an exact Content-Length",
            content.size(), new IsEqual<>(Optional.of((long) data.length)));
        assertThat(
            "a temp file must have been used to spill the excess over the in-memory cap",
            countOverflowFiles(), lessThan(overflowFilesBefore + 2)
        );
        assertTrue(countOverflowFiles() >= overflowFilesBefore, "no leaked overflow file count regression");

        final byte[] consumed = content.asBytesFuture().get(5, TimeUnit.SECONDS);
        assertArrayEquals(data, consumed, "bytes byte-identical across the memory+disk split");
        assertThat(
            "overflow temp file cleaned up once fully consumed",
            countOverflowFiles(), equalTo(overflowFilesBefore)
        );
    }

    @Test
    @DisplayName("multipart-disabled (1-arg ctor): a payload smaller than the memory cap never touches disk")
    void knownSizeProbeStaysInMemoryForSmallPayload() throws Exception {
        final byte[] data = randomBytes(4096);
        final int overflowFilesBefore = countOverflowFiles();

        final Content content = new EstimatedContentCompliment(
            new Content.From(Optional.empty(), Flowable.just(ByteBuffer.wrap(data)))
        ).estimate().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(content.size(), new IsEqual<>(Optional.of((long) data.length)));
        assertThat("no overflow file created for a small payload",
            countOverflowFiles(), equalTo(overflowFilesBefore));
        assertArrayEquals(data, content.asBytesFuture().get(5, TimeUnit.SECONDS), "bytes preserved");
    }

    @Test
    @DisplayName("multipart-disabled (1-arg ctor): upstream error propagates and cleans up any overflow file")
    void knownSizeProbePropagatesUpstreamError() throws IOException {
        final Flowable<ByteBuffer> failing = Flowable
            .just(ByteBuffer.wrap(randomBytes(1024)))
            .concatWith(Flowable.error(new IOException("upstream dropped")));
        final int overflowFilesBefore = countOverflowFiles();

        final ExecutionException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            ExecutionException.class,
            () -> new EstimatedContentCompliment(new Content.From(Optional.empty(), failing))
                .estimate().toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        assertThat("original IOException surfaces, not silently swallowed",
            thrown.getCause(), org.hamcrest.Matchers.instanceOf(IOException.class));
        assertThat("no overflow file leaked on error",
            countOverflowFiles(), equalTo(overflowFilesBefore));
    }

    private static byte[] randomBytes(final int size) {
        final byte[] data = new byte[size];
        new java.util.Random(7).nextBytes(data);
        return data;
    }

    private static Flowable<ByteBuffer> chunked(final byte[] data, final int chunkSize) {
        return Flowable.range(0, (data.length + chunkSize - 1) / chunkSize)
            .map(i -> {
                final int from = i * chunkSize;
                final int to = Math.min(from + chunkSize, data.length);
                return ByteBuffer.wrap(Arrays.copyOfRange(data, from, to));
            });
    }

    private static int countOverflowFiles() throws IOException {
        final Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        if (!Files.exists(tempDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(tempDir)) {
            return (int) stream
                .filter(p -> p.getFileName().toString().startsWith("pantera-s3-estimate-"))
                .count();
        }
    }

    /**
     * Cold {@link Publisher} that hands out {@code chunkSize}-sized
     * {@link ByteBuffer} slices of {@code data} strictly in response to
     * {@code request(n)}, synchronously, and counts exactly how many bytes
     * (and chunks) have been emitted so far -- the structural probe for
     * "did the caller pull the whole body, or only a bounded prefix".
     */
    private static final class CountingChunkedPublisher implements Publisher<ByteBuffer> {
        private final byte[] data;
        private final int chunkSize;
        private final AtomicInteger emitted = new AtomicInteger();

        CountingChunkedPublisher(final byte[] data, final int chunkSize) {
            this.data = data;
            this.chunkSize = chunkSize;
        }

        int emittedBytes() {
            return this.emitted.get();
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> sub) {
            sub.onSubscribe(new ChunkSubscription(sub));
        }

        /** Per-subscription cursor + demand handling. */
        private final class ChunkSubscription implements Subscription {
            private final Subscriber<? super ByteBuffer> sub;
            private int pos;
            private boolean done;

            ChunkSubscription(final Subscriber<? super ByteBuffer> sub) {
                this.sub = sub;
            }

            @Override
            public void request(final long n) {
                long remaining = n;
                while (remaining > 0 && !this.done) {
                    if (this.pos >= CountingChunkedPublisher.this.data.length) {
                        this.done = true;
                        this.sub.onComplete();
                        return;
                    }
                    final int end = Math.min(
                        this.pos + CountingChunkedPublisher.this.chunkSize,
                        CountingChunkedPublisher.this.data.length
                    );
                    final byte[] slice = Arrays.copyOfRange(
                        CountingChunkedPublisher.this.data, this.pos, end
                    );
                    this.pos = end;
                    CountingChunkedPublisher.this.emitted.addAndGet(slice.length);
                    this.sub.onNext(ByteBuffer.wrap(slice));
                    remaining--;
                }
            }

            @Override
            public void cancel() {
                this.done = true;
            }
        }
    }
}
