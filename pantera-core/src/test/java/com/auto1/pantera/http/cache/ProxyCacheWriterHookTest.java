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
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.fault.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link ProxyCacheWriter} {@code onCacheWrite} extension
 * point added in Phase 3 / Task 11. Phase 4's {@code PrefetchDispatcher}
 * will be the first real consumer; this test pins the contract so that
 * callback exceptions do not affect the cache-write path.
 *
 * @since 2.2.0
 */
final class ProxyCacheWriterHookTest {

    /** Cache key for a representative primary artifact. */
    private static final Key PRIMARY_KEY = new Key.From("com/example/foo/1.0/foo-1.0.jar");

    /** Representative primary body. */
    private static final byte[] PRIMARY_BYTES =
        "primary-bytes-for-hook-test".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("writeWithSidecars: callback fires after successful sync write with event fields populated")
    void invokesCallbackAfterSuccessfulSyncWrite() throws Exception {
        final Storage cache = new InMemoryStorage();
        final AtomicReference<CacheWriteEvent> captured = new AtomicReference<>();
        final ProxyCacheWriter writer = new ProxyCacheWriter(
            cache, "maven-proxy", null, captured::set
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES)));

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            "http://upstream/foo-1.0.jar",
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            sidecars,
            null
        ).toCompletableFuture().join();

        assertThat("Ok result", result, instanceOf(Result.Ok.class));
        final CacheWriteEvent event = captured.get();
        assertNotNull(event, "callback fired with event");
        assertEquals("maven-proxy", event.repoName(), "repo name");
        assertEquals(PRIMARY_KEY.string(), event.urlPath(), "url path");
        assertEquals((long) PRIMARY_BYTES.length, event.sizeBytes(), "size in bytes");
        assertThat("bytesOnDisk path supplied", event.bytesOnDisk(), notNullValue());
        assertThat("writtenAt supplied", event.writtenAt(), notNullValue());
    }

    @Test
    @DisplayName("writeAndVerify: callback fires after commitAsync persists primary + sidecars")
    void invokesCallbackAfterCommitAsync() throws Exception {
        final Storage cache = new InMemoryStorage();
        final AtomicReference<CacheWriteEvent> captured = new AtomicReference<>();
        final ProxyCacheWriter writer = new ProxyCacheWriter(
            cache, "maven-proxy", null, captured::set
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES)));

        final Result<ProxyCacheWriter.VerifiedArtifact> verified = writer.writeAndVerify(
            PRIMARY_KEY,
            "http://upstream/foo-1.0.jar",
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            sidecars,
            null
        ).toCompletableFuture().join();
        assertThat("verified ok", verified, instanceOf(Result.Ok.class));
        // Hook fires on commit, not on verify.
        assertEquals(null, captured.get(), "no fire before commit");

        final ProxyCacheWriter.VerifiedArtifact artifact =
            ((Result.Ok<ProxyCacheWriter.VerifiedArtifact>) verified).value();
        artifact.commitAsync().toCompletableFuture().join();

        final CacheWriteEvent event = captured.get();
        assertNotNull(event, "callback fired after commit");
        assertEquals("maven-proxy", event.repoName(), "repo name");
        assertEquals(PRIMARY_KEY.string(), event.urlPath(), "url path");
        assertThat("size > 0", event.sizeBytes(), greaterThan(0L));
        assertThat("bytesOnDisk supplied", event.bytesOnDisk(), notNullValue());
    }

    @Test
    @DisplayName("callback exception does NOT prevent the cache write from succeeding")
    void callbackThrowingDoesNotAffectWrite() throws Exception {
        final Storage cache = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final Consumer<CacheWriteEvent> alwaysThrows = e -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom from callback");
        };
        final ProxyCacheWriter writer = new ProxyCacheWriter(
            cache, "maven-proxy", null, alwaysThrows
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES)));

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            "http://upstream/foo-1.0.jar",
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            sidecars,
            null
        ).toCompletableFuture().join();

        assertThat("Ok despite callback throwing", result, instanceOf(Result.Ok.class));
        assertEquals(1, calls.get(), "callback was invoked exactly once");
        assertTrue(cache.exists(PRIMARY_KEY).join(), "primary persisted in cache");
        assertTrue(
            cache.exists(new Key.From(PRIMARY_KEY.string() + ".sha1")).join(),
            "sidecar persisted in cache"
        );
    }

    @Test
    @DisplayName("integrity rejection does NOT fire the onWrite callback")
    void integrityRejectionDoesNotFireCallback() throws Exception {
        final Storage cache = new InMemoryStorage();
        final AtomicReference<CacheWriteEvent> captured = new AtomicReference<>();
        final ProxyCacheWriter writer = new ProxyCacheWriter(
            cache, "maven-proxy", null, captured::set
        );
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        // Wrong claim → integrity reject.
        sidecars.put(ChecksumAlgo.SHA1, sidecarServing(
            "ffffffffffffffffffffffffffffffffffffffff"
        ));

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            "http://upstream/foo-1.0.jar",
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            sidecars,
            null
        ).toCompletableFuture().join();

        assertThat("Err result", result, instanceOf(Result.Err.class));
        assertEquals(null, captured.get(), "callback NOT fired on integrity rejection");
    }

    @Test
    @DisplayName("default constructor (no callback) still works — backwards-compatible")
    void defaultConstructorIsNoOp() throws Exception {
        final Storage cache = new InMemoryStorage();
        // Old-style construction must still compile + behave correctly.
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
            new EnumMap<>(ChecksumAlgo.class);
        sidecars.put(ChecksumAlgo.SHA1, sidecarServing(sha1Hex(PRIMARY_BYTES)));

        final Result<Void> result = writer.writeWithSidecars(
            PRIMARY_KEY,
            "http://upstream/foo-1.0.jar",
            () -> CompletableFuture.completedFuture(new ByteArrayInputStream(PRIMARY_BYTES)),
            sidecars,
            null
        ).toCompletableFuture().join();

        assertThat("Ok with default no-op callback", result, instanceOf(Result.Ok.class));
        assertThat(
            "primary in cache",
            cache.exists(PRIMARY_KEY).join(),
            equalTo(true)
        );
    }

    // ===== helpers =====

    private static Supplier<CompletionStage<Optional<InputStream>>> sidecarServing(
        final String hex
    ) {
        final byte[] body = hex.getBytes(StandardCharsets.UTF_8);
        return () -> CompletableFuture.completedFuture(
            Optional.of(new ByteArrayInputStream(body))
        );
    }

    private static String sha1Hex(final byte[] body) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
