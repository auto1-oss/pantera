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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.context.RequestContext;
import com.auto1.pantera.http.fault.Fault;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxyCacheWriter} — the v2.2 atomic proxy cache writer that
 * eliminates the Maven {@code .pom.sha1} mismatches (§9.5 of
 * {@code docs/analysis/v2.2-target-architecture.md}).
 *
 * <p>Each test uses a real {@link InMemoryStorage} or {@link FileStorage}; the
 * upstream is modelled by closing over byte[] bodies served from test helpers.
 * The writer never buffers the primary body on heap — it streams to a
 * {@code Files.createTempFile} temp path, and the tests assert this
 * temp path is cleaned up after every terminal outcome.
 *
 * @since 2.2.0
 */
@SuppressWarnings("PMD.TooManyMethods")
final class ProxyCacheWriterTest {

    /** Pretend the client asked for this artifact on a Maven upstream. */
    private static final String UPSTREAM_URI =
        "https://repo.upstream.example/releases/com/fasterxml/oss-parent/58/oss-parent-58.pom";

    /** Cache key under which the primary lands. */
    private static final Key PRIMARY_KEY =
        new Key.From("com/fasterxml/oss-parent/58/oss-parent-58.pom");

    /** Representative primary body. */
    private static final byte[] PRIMARY_BYTES =
        "<project><modelVersion>4.0.0</modelVersion></project>\n".getBytes(StandardCharsets.UTF_8);

    /** Arbitrary request context, only used for log trace-id. */
    private static final RequestContext CTX =
        new RequestContext("trace-abc", "req-1", "maven-proxy", UPSTREAM_URI);

    // ===== verificationFailure_rejectsWrite =====

    @Test
    @DisplayName("sidecar disagreement → Err(UpstreamIntegrity), cache untouched, temp file cleaned")
    void verificationFailure_rejectsWrite() throws IOException {
        final Storage cache = new InMemoryStorage();
        final int tempFilesBefore = countTempFiles();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        // Real bytes; wrong SHA-1 claim (the oss-parent-58 symptom from the doc §9.5).
        final String wrongSha1 = "15ce8a2c447057a4cfffd7a1d57b80937d293e7a";

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(wrongSha1.getBytes(StandardCharsets.UTF_8))),
            CTX
        ).toCompletableFuture().join();

        assertThat("Err result", result, instanceOf(Result.Err.class));
        final Fault fault = ((Result.Err<Void>) result).fault();
        assertThat("UpstreamIntegrity fault", fault, instanceOf(Fault.UpstreamIntegrity.class));
        final Fault.UpstreamIntegrity ui = (Fault.UpstreamIntegrity) fault;
        assertEquals(ChecksumAlgo.SHA1, ui.algo(), "algo carried");
        assertEquals(wrongSha1, ui.sidecarClaim(), "claim carried");
        assertEquals(sha1Hex(PRIMARY_BYTES), ui.computed(), "computed carried");
        assertFalse(cache.exists(PRIMARY_KEY).join(), "primary NOT in cache");
        assertFalse(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "sidecar NOT in cache"
        );
        assertEquals(
            tempFilesBefore,
            countTempFiles(),
            "temp file cleaned up after rejected write"
        );
    }

    // ===== verificationSuccess_atomicallyMoves =====

    @Test
    @DisplayName("matching sidecars → primary + every sidecar readable from cache")
    void verificationSuccess_atomicallyMoves() {
        final Storage cache = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new LinkedHashMap<>();
        sidecars.put(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES).getBytes(StandardCharsets.UTF_8)));
        sidecars.put(ChecksumAlgo.MD5, sidecarServing(md5Hex(PRIMARY_BYTES).getBytes(StandardCharsets.UTF_8)));
        sidecars.put(ChecksumAlgo.SHA256, sidecarServing(sha256Hex(PRIMARY_BYTES).getBytes(StandardCharsets.UTF_8)));

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            sidecars,
            CTX
        ).toCompletableFuture().join();

        assertThat("Ok result", result, instanceOf(Result.Ok.class));
        assertTrue(cache.exists(PRIMARY_KEY).join(), "primary in cache");
        assertArrayEquals(
            PRIMARY_BYTES,
            cache.value(PRIMARY_KEY).join().asBytes(),
            "primary bytes match"
        );
        assertArrayEquals(
            sha1Hex(PRIMARY_BYTES).getBytes(StandardCharsets.UTF_8),
            cache.value(new Key.From(PRIMARY_KEY.string() + ".sha1")).join().asBytes(),
            "sha1 sidecar persisted"
        );
        assertArrayEquals(
            md5Hex(PRIMARY_BYTES).getBytes(StandardCharsets.UTF_8),
            cache.value(new Key.From(PRIMARY_KEY.string() + ".md5")).join().asBytes(),
            "md5 sidecar persisted"
        );
        assertArrayEquals(
            sha256Hex(PRIMARY_BYTES).getBytes(StandardCharsets.UTF_8),
            cache.value(new Key.From(PRIMARY_KEY.string() + ".sha256")).join().asBytes(),
            "sha256 sidecar persisted"
        );
    }

    // ===== sidecarAbsent_stillWrites =====

    @Test
    @DisplayName("upstream 404 on every sidecar → primary still written")
    void sidecarAbsent_stillWrites() {
        final Storage cache = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars = Map.of(
            ChecksumAlgo.SHA1, sidecar404(),
            ChecksumAlgo.MD5, sidecar404()
        );

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            sidecars,
            CTX
        ).toCompletableFuture().join();

        assertThat("Ok result", result, instanceOf(Result.Ok.class));
        assertTrue(cache.exists(PRIMARY_KEY).join(), "primary in cache");
        assertFalse(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "absent sidecar NOT synthesized"
        );
    }

    // ===== sidecar with trailing junk (hex *filename) =====

    @Test
    @DisplayName("sidecar body 'hex *filename' accepted — hex extracted before comparison")
    void sidecarNormalisation_acceptsHexWithFilename() {
        final Storage cache = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final String body = sha1Hex(PRIMARY_BYTES) + "  oss-parent-58.pom\n";

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(body.getBytes(StandardCharsets.UTF_8))),
            CTX
        ).toCompletableFuture().join();

        assertThat("Ok result", result, instanceOf(Result.Ok.class));
    }

    // ===== atomicity_noPartialStateOnCrash =====

    @Test
    @DisplayName("primary save fails → nothing in cache, temp file cleaned")
    void atomicity_noPartialStateOnCrash() throws IOException {
        final CrashingStorage cache = new CrashingStorage();
        cache.failOn(PRIMARY_KEY);
        final int tempFilesBefore = countTempFiles();
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES).getBytes(StandardCharsets.UTF_8))),
            CTX
        ).toCompletableFuture().join();

        assertThat("Err on storage crash", result, instanceOf(Result.Err.class));
        assertThat(
            "StorageUnavailable carried",
            ((Result.Err<Void>) result).fault(),
            instanceOf(Fault.StorageUnavailable.class)
        );
        assertFalse(cache.exists(PRIMARY_KEY).join(), "primary NOT in cache");
        assertFalse(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "sidecar NOT in cache"
        );
        assertEquals(tempFilesBefore, countTempFiles(), "temp file cleaned");
    }

    @Test
    @DisplayName("sidecar save fails after primary lands → primary + sidecar rolled back")
    void atomicity_rollbackOnSidecarFailure() {
        final CrashingStorage cache = new CrashingStorage();
        final Key sha1Key = new Key.From(PRIMARY_KEY.string() + ".sha1");
        cache.failOn(sha1Key);
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES).getBytes(StandardCharsets.UTF_8))),
            CTX
        ).toCompletableFuture().join();

        assertThat("Err on partial failure", result, instanceOf(Result.Err.class));
        assertFalse(cache.exists(PRIMARY_KEY).join(), "primary rolled back");
        assertFalse(cache.exists(sha1Key).join(), "sidecar rolled back");
    }

    // ===== swrCoherence =====

    @Test
    @DisplayName("stale primary + sidecar → fresh upstream → both updated atomically")
    void swrCoherence(@TempDir final Path tempDir) throws Exception {
        final FileStorage cache = new FileStorage(tempDir);
        // Seed with STALE primary + matching STALE sidecar — both consistent but stale.
        final byte[] staleBytes = "stale content\n".getBytes(StandardCharsets.UTF_8);
        cache.save(PRIMARY_KEY, new Content.From(staleBytes)).join();
        cache.save(
            new Key.From(PRIMARY_KEY.string() + ".sha1"),
            new Content.From(sha1Hex(staleBytes).getBytes(StandardCharsets.UTF_8))
        ).join();

        // Now refetch with a fresh (different) primary + matching fresh sidecar.
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final byte[] freshBytes = "fresh content\n".getBytes(StandardCharsets.UTF_8);
        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(freshBytes)),
            Map.of(
                ChecksumAlgo.SHA1, sidecarServing(sha1Hex(freshBytes).getBytes(StandardCharsets.UTF_8)),
                ChecksumAlgo.SHA256, sidecarServing(sha256Hex(freshBytes).getBytes(StandardCharsets.UTF_8))
            ),
            CTX
        ).toCompletableFuture().join();

        assertThat("Ok result", result, instanceOf(Result.Ok.class));
        // Both files now reflect the FRESH content — no split brain.
        assertArrayEquals(freshBytes, cache.value(PRIMARY_KEY).join().asBytes(), "primary is fresh");
        assertEquals(
            sha1Hex(freshBytes),
            new String(
                cache.value(new Key.From(PRIMARY_KEY.string() + ".sha1")).join().asBytes(),
                StandardCharsets.UTF_8
            ),
            "sidecar matches fresh primary"
        );
        // Cross-hash consistency invariant: sidecar bytes recompute to primary's hex.
        final byte[] primaryReread = cache.value(PRIMARY_KEY).join().asBytes();
        final byte[] sidecarBytes = cache.value(new Key.From(PRIMARY_KEY.string() + ".sha1")).join().asBytes();
        assertEquals(
            sha1Hex(primaryReread),
            new String(sidecarBytes, StandardCharsets.UTF_8),
            "cache invariant: sidecar hex == SHA-1 of primary bytes"
        );
    }

    @Test
    @DisplayName("stale pair + upstream brings a MISMATCHED fresh pair → reject, keep stale intact")
    void swrCoherence_rejectMismatchedRefetch(@TempDir final Path tempDir) {
        final FileStorage cache = new FileStorage(tempDir);
        final byte[] staleBytes = "stale content\n".getBytes(StandardCharsets.UTF_8);
        final String staleSha1 = sha1Hex(staleBytes);
        cache.save(PRIMARY_KEY, new Content.From(staleBytes)).join();
        cache.save(
            new Key.From(PRIMARY_KEY.string() + ".sha1"),
            new Content.From(staleSha1.getBytes(StandardCharsets.UTF_8))
        ).join();

        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final byte[] freshBytes = "fresh content\n".getBytes(StandardCharsets.UTF_8);
        // Upstream serves a sha1 claim that DOES NOT match the fresh primary bytes.
        final String bogusClaim = "ffffffffffffffffffffffffffffffffffffffff";
        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(freshBytes)),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(bogusClaim.getBytes(StandardCharsets.UTF_8))),
            CTX
        ).toCompletableFuture().join();

        assertThat("Err(UpstreamIntegrity)", result, instanceOf(Result.Err.class));
        // Stale pair must be intact — the rejected write never mutated the cache.
        assertArrayEquals(staleBytes, cache.value(PRIMARY_KEY).join().asBytes(), "stale primary intact");
        assertEquals(
            staleSha1,
            new String(
                cache.value(new Key.From(PRIMARY_KEY.string() + ".sha1")).join().asBytes(),
                StandardCharsets.UTF_8
            ),
            "stale sidecar intact"
        );
    }

    // ===== integration: real FileStorage roundtrip on the oss-parent-58.pom symptom =====

    @Test
    @DisplayName("oss-parent-58.pom regression: mismatched upstream .sha1 rejects cache write")
    void ossParent58_regressionCheck(@TempDir final Path tempDir) {
        final FileStorage cache = new FileStorage(tempDir);
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "libs-release-local");
        // The exact hex from the production log in §9.5.
        final byte[] upstreamSha1 = "15ce8a2c447057a4cfffd7a1d57b80937d293e7a"
            .getBytes(StandardCharsets.UTF_8);
        final byte[] pomBytes = "<project>oss-parent-58</project>".getBytes(StandardCharsets.UTF_8);

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            UPSTREAM_URI,
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(pomBytes)),
            Map.of(ChecksumAlgo.SHA1, sidecarServing(upstreamSha1)),
            CTX
        ).toCompletableFuture().join();

        assertThat("Err", result, instanceOf(Result.Err.class));
        assertThat(
            "UpstreamIntegrity fault",
            ((Result.Err<Void>) result).fault(),
            instanceOf(Fault.UpstreamIntegrity.class)
        );
        assertFalse(cache.exists(PRIMARY_KEY).join(), "no primary cached");
        assertFalse(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "no sidecar cached"
        );
    }

    // ===== writeAndVerify / VerifiedArtifact =====

    @Test
    @DisplayName("writeAndVerify returns VerifiedArtifact with temp file before commit")
    void writeAndVerifyReturnsTempFile() throws Exception {
        final Storage storage = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(storage, "test-repo");
        final byte[] body = "hello-artifact".getBytes(StandardCharsets.UTF_8);
        final String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(body)
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA256, () -> CompletableFuture.completedFuture(
            Optional.of(new ByteArrayInputStream(sha256.getBytes(StandardCharsets.UTF_8)))
        ));
        final Result<ProxyCacheWriter.VerifiedArtifact> result =
            writer.writeAndVerify(
                new Key.From("test/artifact.jar"),
                "http://upstream/artifact.jar",
                () -> CompletableFuture.completedFuture(new ByteArrayInputStream(body)),
                sidecars,
                null
            ).toCompletableFuture().join();
        assertThat(result, instanceOf(Result.Ok.class));
        final ProxyCacheWriter.VerifiedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.VerifiedArtifact>) result).value();
        assertTrue(Files.exists(artifact.tempFile()));
        assertEquals(body.length, Files.size(artifact.tempFile()));
        assertFalse(storage.exists(new Key.From("test/artifact.jar")).join());
        artifact.commitAsync().toCompletableFuture().join();
    }

    @Test
    @DisplayName("commitAsync persists primary and sidecars to storage")
    void commitAsyncPersistsToStorage() throws Exception {
        final Storage storage = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(storage, "test-repo");
        final byte[] body = "commit-test".getBytes(StandardCharsets.UTF_8);
        final String sha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(body)
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA256, () -> CompletableFuture.completedFuture(
            Optional.of(new ByteArrayInputStream(sha256.getBytes(StandardCharsets.UTF_8)))
        ));
        final Result<ProxyCacheWriter.VerifiedArtifact> result =
            writer.writeAndVerify(
                new Key.From("test/commit.jar"),
                "http://upstream/commit.jar",
                () -> CompletableFuture.completedFuture(new ByteArrayInputStream(body)),
                sidecars,
                null
            ).toCompletableFuture().join();
        final ProxyCacheWriter.VerifiedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.VerifiedArtifact>) result).value();
        final Result<Void> commitResult = artifact.commitAsync().toCompletableFuture().join();
        assertThat(commitResult, instanceOf(Result.Ok.class));
        assertTrue(storage.exists(new Key.From("test/commit.jar")).join());
        assertTrue(storage.exists(new Key.From("test/commit.jar.sha256")).join());
        assertFalse(Files.exists(artifact.tempFile()));
    }

    @Test
    @DisplayName("writeAndVerify rejects on sidecar mismatch")
    void writeAndVerifyRejectsOnMismatch() throws Exception {
        final Storage storage = new InMemoryStorage();
        final ProxyCacheWriter writer = new ProxyCacheWriter(storage, "test-repo");
        final byte[] body = "mismatch-test".getBytes(StandardCharsets.UTF_8);
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA256, () -> CompletableFuture.completedFuture(
            Optional.of(new ByteArrayInputStream("deadbeef".getBytes(StandardCharsets.UTF_8)))
        ));
        final Result<ProxyCacheWriter.VerifiedArtifact> result =
            writer.writeAndVerify(
                new Key.From("test/bad.jar"),
                "http://upstream/bad.jar",
                () -> CompletableFuture.completedFuture(new ByteArrayInputStream(body)),
                sidecars,
                null
            ).toCompletableFuture().join();
        assertThat(result, instanceOf(Result.Err.class));
        final Fault fault = ((Result.Err<ProxyCacheWriter.VerifiedArtifact>) result).fault();
        assertThat(fault, instanceOf(Fault.UpstreamIntegrity.class));
    }

    // ===== helpers =====

    private static void assertArrayEquals(
        final byte[] expected, final byte[] actual, final String message
    ) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, message);
    }

    private static Supplier<CompletionStage<Optional<InputStream>>> sidecarServing(
        final byte[] body
    ) {
        return () -> CompletableFuture.completedFuture(
            Optional.of(new ByteArrayInputStream(body))
        );
    }

    private static Supplier<CompletionStage<Optional<InputStream>>> sidecar404() {
        return () -> CompletableFuture.completedFuture(Optional.empty());
    }

    private static String sha1Hex(final byte[] body) {
        return hex("SHA-1", body);
    }

    private static String sha256Hex(final byte[] body) {
        return hex("SHA-256", body);
    }

    private static String md5Hex(final byte[] body) {
        return hex("MD5", body);
    }

    private static String hex(final String algo, final byte[] body) {
        try {
            final MessageDigest md = MessageDigest.getInstance(algo);
            return HexFormat.of().formatHex(md.digest(body));
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static int countTempFiles() throws IOException {
        final Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        if (!Files.exists(tempDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(tempDir)) {
            return (int) stream
                .filter(p -> p.getFileName().toString().startsWith("pantera-proxy-"))
                .count();
        }
    }

    /**
     * A storage decorator that fails on a specific key, to exercise partial-
     * failure rollback without depending on OS behaviour.
     */
    private static final class CrashingStorage implements Storage {
        private final InMemoryStorage delegate = new InMemoryStorage();
        private Key failing;

        void failOn(final Key key) {
            this.failing = key;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return this.delegate.exists(key);
        }

        @Override
        public CompletableFuture<java.util.Collection<Key>> list(final Key prefix) {
            return this.delegate.list(prefix);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            if (key.equals(this.failing)) {
                // Drain the content so the caller's stream doesn't dangle, then fail.
                return content.asBytesFuture().thenCompose(ignored ->
                    CompletableFuture.failedFuture(new RuntimeException("boom"))
                );
            }
            return this.delegate.save(key, content);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return this.delegate.move(source, destination);
        }

        @Override
        public CompletableFuture<? extends com.auto1.pantera.asto.Meta> metadata(final Key key) {
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
            final Key key, final java.util.function.Function<Storage, CompletionStage<T>> op
        ) {
            return this.delegate.exclusively(key, op);
        }
    }
}
