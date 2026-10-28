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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.cache.ProxyCacheWriter.RefCountedTempFile;
import com.auto1.pantera.http.cache.ProxyCacheWriter.VerifiedArtifact;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import io.reactivex.Flowable;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the WS3.1 streaming {@code commitVerified} path and its
 * reference-counted temp-file lifecycle: the download temp file is safely
 * shared between the client tee ({@link VerifiedArtifact#contentFromTempFile()})
 * and the cache commit ({@link VerifiedArtifact#commitAsync()}), deleted
 * exactly once when both are done, never while either still reads it — and the
 * commit streams the file into storage in bounded chunks with the digest and
 * bytes preserved (no whole-artifact heap buffer).
 *
 * @since 2.3.0
 */
final class ProxyCacheWriterRefCountCommitTest {

    /** Upstream digest ({@code CHUNK_SIZE} in the writer is 64 KiB). */
    private static final int CHUNK_SIZE = 64 * 1024;

    // ===================================================================
    // RefCountedTempFile primitive — the load-bearing lifecycle logic.
    // ===================================================================

    @Test
    @DisplayName("ref-count: file deleted exactly once, only after the last reader releases")
    void deletesExactlyOnceAfterLastRelease(@TempDir final Path dir) throws Exception {
        final Path file = Files.write(dir.resolve("artifact.bin"), new byte[] { 1, 2, 3 });
        final RefCountedTempFile handle = new RefCountedTempFile(file);
        // Two readers (serve + commit) both retain before either releases.
        handle.retain();
        handle.retain();
        // Serve takes ownership: drops the base reference.
        handle.dropBase();
        assertThat("both readers still hold references", handle.refCount(), new IsEqual<>(2));

        // First reader releases — the second is still active, so NO delete.
        handle.release();
        assertThat("one reader still active", handle.refCount(), new IsEqual<>(1));
        assertTrue(Files.exists(file), "file must survive while a reader is active");
        assertFalse(handle.deleted(), "not deleted while a reader is active");

        // Last reader releases — now the file is deleted, exactly once.
        handle.release();
        assertThat("all references released", handle.refCount(), new IsEqual<>(0));
        assertTrue(handle.deleted(), "deleted after the last release");
        assertFalse(Files.exists(file), "file physically removed on last release");
    }

    @Test
    @DisplayName("ref-count: base reference alone keeps the file alive between sequential readers")
    void baseReferenceBridgesSequentialReaders(@TempDir final Path dir) throws Exception {
        final Path file = Files.write(dir.resolve("bridge.bin"), new byte[] { 9 });
        final RefCountedTempFile handle = new RefCountedTempFile(file);
        // Commit runs and fully finishes first (retain then release), but the
        // base reference is still held, so the file survives for a later serve.
        handle.retain();
        handle.release();
        assertTrue(Files.exists(file), "base reference keeps the file alive after commit finishes");
        assertFalse(handle.deleted(), "commit alone does not delete the shared temp file");
        assertThat("base reference remains", handle.refCount(), new IsEqual<>(1));

        // Serve now takes over: retain, drop base (ownership handoff), release.
        handle.retain();
        handle.dropBase();
        handle.release();
        assertTrue(handle.deleted(), "deleted once the serve path releases and the base is dropped");
        assertFalse(Files.exists(file), "file removed after both consumers are done");
    }

    @Test
    @DisplayName("ref-count: dropBase is idempotent and retain-after-delete is refused")
    void dropBaseIdempotentAndNoResurrection(@TempDir final Path dir) throws Exception {
        final Path file = Files.write(dir.resolve("once.bin"), new byte[] { 7 });
        final RefCountedTempFile handle = new RefCountedTempFile(file);
        handle.dropBase();
        handle.dropBase();
        assertTrue(handle.deleted(), "dropping the base with no readers deletes exactly once");
        assertFalse(Files.exists(file), "file removed");
        assertThrows(
            IllegalStateException.class,
            handle::retain,
            "a retain must never resurrect an already-deleted file"
        );
    }

    // ===================================================================
    // End-to-end: serve + commit share the temp file through the real API.
    // ===================================================================

    @Test
    @DisplayName("commit finishing does not delete the temp file while the serve still holds it")
    void tempFileSurvivesUntilBothServeAndCommitRelease() throws Exception {
        final Storage storage = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(storage, "maven-proxy");
        final byte[] body = "shared-temp-body".getBytes(StandardCharsets.UTF_8);
        final VerifiedArtifact artifact = verify(writer, "shared/a.jar", body);
        final RefCountedTempFile handle = artifact.handle();
        assertThat("only the base reference at first", handle.refCount(), new IsEqual<>(1));

        // Build the serve body (retains + drops base) but do NOT consume yet.
        final Content serve = artifact.contentFromTempFile();
        assertThat("serve holds one reference (base dropped)", handle.refCount(), new IsEqual<>(1));

        // Commit fully, then wait for it. The commit retained + released around
        // its store; the serve reference must still keep the file alive.
        final Result<Void> commit = artifact.commitAsync().toCompletableFuture().join();
        assertThat("commit ok", commit, instanceOf(Result.Ok.class));
        assertFalse(handle.deleted(), "temp file NOT deleted while the serve still holds it");
        assertTrue(Files.exists(artifact.tempFile()), "temp file present for the pending serve");
        assertThat("serve reference remains after commit", handle.refCount(), new IsEqual<>(1));

        // Now drain the serve body — the last reference releases and deletes.
        final byte[] served = serve.asBytesFuture().join();
        assertArrayEquals(body, served, "serve streamed the exact bytes");
        assertTrue(handle.deleted(), "temp file deleted once both consumers released");
        assertFalse(Files.exists(artifact.tempFile()), "temp file removed exactly once");

        // Integrity preserved through the commit path too.
        assertArrayEquals(
            body,
            storage.value(new Key.From("shared/a.jar")).join().asBytesFuture().join(),
            "committed cache bytes are byte-identical to the upstream body"
        );
        assertTrue(
            storage.exists(new Key.From("shared/a.jar.sha256")).join(),
            "verified sidecar committed alongside the primary"
        );
    }

    // ===================================================================
    // Streaming: the commit hands storage a bounded, chunked Content.
    // ===================================================================

    @Test
    @DisplayName("commit streams the primary in bounded chunks (no whole-artifact heap buffer)")
    void commitStreamsPrimaryInBoundedChunks() throws Exception {
        // 200 KiB > CHUNK_SIZE (64 KiB): a whole-body buffer would emit one
        // giant ByteBuffer; the streaming path emits several bounded chunks.
        final byte[] body = new byte[200 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }
        final ChunkRecordingStorage storage = new ChunkRecordingStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(storage, "maven-proxy");
        final Key key = new Key.From("big/artifact.jar");
        final VerifiedArtifact artifact = verify(writer, key.string(), body);

        final Result<Void> commit = artifact.commitAsync().toCompletableFuture().join();
        assertThat("commit ok", commit, instanceOf(Result.Ok.class));

        final List<Integer> chunks = storage.chunkSizes(key.string());
        assertThat("primary streamed in more than one chunk", chunks.size(), greaterThan(1));
        for (final int chunk : chunks) {
            assertThat("every chunk is bounded by CHUNK_SIZE", chunk, lessThanOrEqualTo(CHUNK_SIZE));
        }
        // The streamed bytes reassemble to exactly the upstream body — the
        // streaming digest/commit is byte-for-byte lossless.
        assertArrayEquals(body, storage.bytes(key.string()), "streamed bytes preserved");
        // Serve path streams the identical bytes and cleans up the temp file.
        assertArrayEquals(body, artifact.contentFromTempFile().asBytesFuture().join(), "serve identical");
        assertFalse(Files.exists(artifact.tempFile()), "temp file cleaned up after serve");
    }

    // ===================================================================
    // Helpers.
    // ===================================================================

    /** Drive writeAndVerify to a verified artifact with a matching SHA-256 sidecar. */
    private static VerifiedArtifact verify(
        final ProxyCacheWriter writer, final String path, final byte[] body
    ) throws Exception {
        final String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(body)
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA256, () -> CompletableFuture.completedFuture(
            Optional.of(new ByteArrayInputStream(sha256.getBytes(StandardCharsets.UTF_8)))
        ));
        final Result<VerifiedArtifact> result = writer.writeAndVerify(
            new Key.From(path),
            "http://upstream/" + path,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(body)),
            sidecars,
            null
        ).toCompletableFuture().join();
        assertThat("verify ok", result, instanceOf(Result.Ok.class));
        return ((Result.Ok<VerifiedArtifact>) result).value();
    }

    /**
     * Storage that drains every {@code save()} content itself, recording the
     * per-key chunk sizes and reassembled bytes, then persists the bytes into
     * a backing {@link InMemoryStorage} so read-backs still work. Draining the
     * content proves the primary arrives as bounded chunks and never as a
     * single whole-artifact buffer.
     */
    private static final class ChunkRecordingStorage implements Storage {

        private final InMemoryStorage delegate = new InMemoryStorage();
        private final Map<String, List<Integer>> sizes = new ConcurrentHashMap<>();
        private final Map<String, byte[]> reassembled = new ConcurrentHashMap<>();

        List<Integer> chunkSizes(final String key) {
            return this.sizes.getOrDefault(key, List.of());
        }

        byte[] bytes(final String key) {
            return this.reassembled.get(key);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            final List<Integer> chunkSizes = new CopyOnWriteArrayList<>();
            final ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Flowable.fromPublisher(content).blockingForEach(buf -> {
                final ByteBuffer dup = buf.duplicate();
                final byte[] arr = new byte[dup.remaining()];
                dup.get(arr);
                chunkSizes.add(arr.length);
                sink.write(arr, 0, arr.length);
            });
            final byte[] all = sink.toByteArray();
            this.sizes.put(key.string(), chunkSizes);
            this.reassembled.put(key.string(), all);
            return this.delegate.save(key, new Content.From(all));
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
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return this.delegate.move(source, destination);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return this.delegate.metadata(key);
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
        public <T> CompletionStage<T> exclusively(
            final Key key, final Function<Storage, CompletionStage<T>> operation
        ) {
            return this.delegate.exclusively(key, operation);
        }
    }
}
