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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import io.reactivex.Flowable;
import org.hamcrest.Description;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Tests for {@link Upload}.
 */
class UploadTest {

    /**
     * Slice being tested.
     */
    private Upload upload;

    /**
     * Storage.
     */
    private Storage storage;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.upload = new Upload(this.storage, "test", UUID.randomUUID().toString());
    }

    @Test
    void shouldCreateDataOnStart() {
        this.upload.start().toCompletableFuture().join();
        MatcherAssert.assertThat(
            this.storage.list(this.upload.root()).join().isEmpty(),
            Matchers.is(false)
        );
    }

    @Test
    void shouldSaveStartedDateWhenLoadingIsStarted() {
        final Instant time = LocalDateTime.of(2020, Month.MAY, 19, 12, 58, 11)
            .atZone(ZoneOffset.UTC).toInstant();
        this.upload.start(time).join();
        MatcherAssert.assertThat(
            new String(
                new BlockingStorage(this.storage)
                    .value(new Key.From(this.upload.root(), "started")),
                StandardCharsets.US_ASCII
            ), Matchers.equalTo("2020-05-19T12:58:11Z")
        );
    }

    @Test
    void shouldReturnOffsetWhenAppendedChunk() {
        final byte[] chunk = "sample".getBytes();
        this.upload.start().join();
        final Long offset = this.upload.append(new Content.From(chunk)).join();
        MatcherAssert.assertThat(offset, Matchers.is((long) chunk.length - 1));
    }

    @Test
    void shouldReadAppendedChunk() {
        final byte[] chunk = "chunk".getBytes();
        this.upload.start().join();
        this.upload.append(new Content.From(chunk)).join();
        MatcherAssert.assertThat(
            this.upload,
            new IsUploadWithContent(chunk)
        );
    }

    @Test
    void shouldAppendOrderedChunks() {
        this.upload.start().toCompletableFuture().join();
        final long first = this.upload.append(new Content.From("one".getBytes())).join();
        final long second = this.upload.append(new Content.From("two".getBytes())).join();
        MatcherAssert.assertThat(
            "first chunk ends at offset 2",
            first, new IsEqual<>(2L)
        );
        MatcherAssert.assertThat(
            "second chunk ends at offset 5",
            second, new IsEqual<>(5L)
        );
        MatcherAssert.assertThat(
            "offset reports the total uploaded so far",
            this.upload.offset().join(), new IsEqual<>(5L)
        );
        MatcherAssert.assertThat(
            "the blob is the concatenation of the chunks",
            this.upload, new IsUploadWithContent("onetwo".getBytes())
        );
    }

    @Test
    void shouldRejectOutOfOrderChunk() {
        this.upload.start().toCompletableFuture().join();
        this.upload.append(new Content.From("one".getBytes()), Optional.of(0L)).join();
        MatcherAssert.assertThat(
            Assertions.assertThrows(
                CompletionException.class,
                () -> this.upload.append(new Content.From("two".getBytes()), Optional.of(7L))
                    .join()
            ).getCause(),
            new IsInstanceOf(UploadRangeException.class)
        );
    }

    @Test
    void shouldAcceptChunkStartingAtCurrentOffset() {
        this.upload.start().toCompletableFuture().join();
        this.upload.append(new Content.From("one".getBytes()), Optional.of(0L)).join();
        this.upload.append(new Content.From("two".getBytes()), Optional.of(3L)).join();
        MatcherAssert.assertThat(
            this.upload, new IsUploadWithContent("onetwo".getBytes())
        );
    }

    @Test
    void shouldFailPutWhenConcatenationMismatchesDigest() {
        this.upload.start().toCompletableFuture().join();
        this.upload.append(new Content.From("one".getBytes())).join();
        this.upload.append(new Content.From("two".getBytes())).join();
        MatcherAssert.assertThat(
            Assertions.assertThrows(
                CompletionException.class,
                () -> this.upload.putTo(
                    new CapturePutLayers(), new Digest.Sha256("twoone".getBytes())
                ).join()
            ).getCause(),
            new IsInstanceOf(com.auto1.pantera.docker.error.InvalidDigestException.class)
        );
    }

    @Test
    void shouldAppendedSecondChunkIfFirstOneFailed() {
        this.upload.start().join();
        try {
            this.upload.append(new Content.From(1, Flowable.error(new IllegalStateException())))
                .toCompletableFuture()
                .join();
        } catch (final CompletionException ignored) {
        }
        final byte[] chunk = "content".getBytes();
        this.upload.append(new Content.From(chunk)).join();
        MatcherAssert.assertThat(
            this.upload,
            new IsUploadWithContent(chunk)
        );
    }

    @Test
    void shouldRemoveUploadedFiles() throws ExecutionException, InterruptedException {
        this.upload.start().toCompletableFuture().join();
        final byte[] chunk = "some bytes".getBytes();
        this.upload.append(new Content.From(chunk)).get();
        this.upload.putTo(new CapturePutLayers(), new Digest.Sha256(chunk)).get();
        MatcherAssert.assertThat(
            this.storage.list(this.upload.root()).get(),
            new IsEmptyCollection<>()
        );
    }

    /**
     * Matcher for {@link Upload} content.
     */
    private final class IsUploadWithContent extends TypeSafeMatcher<Upload> {

        /**
         * Expected content.
         */
        private final byte[] content;

        private IsUploadWithContent(final byte[] content) {
            this.content = Arrays.copyOf(content, content.length);
        }

        @Override
        public void describeTo(final Description description) {
            new IsEqual<>(this.content).describeTo(description);
        }

        @Override
        public boolean matchesSafely(final Upload upl) {
            final Digest digest = new Digest.Sha256(this.content);
            final CapturePutLayers fake = new CapturePutLayers();
            upl.putTo(fake, digest).toCompletableFuture().join();
            return new IsEqual<>(this.content).matches(fake.content());
        }
    }

    /**
     * Layers implementation that captures put method content.
     *
     * @since 0.12
     */
    private final class CapturePutLayers implements Layers {

        /**
         * Captured put content.
         */
        private volatile byte[] content;

        @Override
        public CompletableFuture<Digest> put(final BlobSource source) {
            final Key key = new Key.From(UUID.randomUUID().toString());
            source.saveTo(UploadTest.this.storage, key).toCompletableFuture().join();
            this.content = UploadTest.this.storage.value(key)
                .thenCompose(Content::asBytesFuture).join();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> mount(final Blob blob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Optional<Blob>> get(final Digest digest) {
            throw new UnsupportedOperationException();
        }

        public byte[] content() {
            return this.content;
        }
    }
}
