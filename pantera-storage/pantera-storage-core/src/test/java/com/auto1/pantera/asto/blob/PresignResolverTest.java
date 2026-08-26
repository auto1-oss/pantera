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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PresignResolver}: the WS1.7 (spec {@code
 * WS1-storage-for-scale.md} &sect;3.B2) acceptance criteria -- a durably
 * present object resolves to a presigner with zero blob-store contact, a
 * {@code PENDING_WRITE} object never presigns, presign-not-configured never
 * presigns, and nested {@link SubStorage} layers compose the correct
 * fully-qualified key -- proved with an invocation-counting {@link
 * RecordingBlobStore} fake, never wall-clock timing (CLAUDE.md testing
 * doctrine).
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
final class PresignResolverTest {

    private static final Duration FRESHNESS_TTL = Duration.ofMinutes(5);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    @Test
    void durablyPresentKeyResolvesAndPresignsWithZeroBlobStoreCalls(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        fake.seed("a/lib.jar", "hello".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storage = PresignResolverTest.writeThroughStorage(fake, tmp);
        final Key key = new Key.From("a", "lib.jar");
        // Populate the index via a cold fetch first (this DOES touch the
        // blob store once -- the redirect decision itself must not add any
        // further calls on top of it).
        storage.value(key).join();
        final int getCallsAfterFill = fake.getCalls();

        final Optional<PresignResolver.Target> target = PresignResolver.resolve(storage, key);
        MatcherAssert.assertThat("target must resolve", target.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(target.get().durablyPresent(), new IsEqual<>(true));
        final Optional<URI> presigned = target.get().presignIfDurable(600L);
        MatcherAssert.assertThat("must presign", presigned.isPresent(), new IsEqual<>(true));

        MatcherAssert.assertThat(
            "resolving + presigning must not touch the blob store",
            fake.getCalls(), new IsEqual<>(getCallsAfterFill)
        );
        MatcherAssert.assertThat(fake.headCalls(), new IsEqual<>(0));
        MatcherAssert.assertThat(fake.existsCalls(), new IsEqual<>(0));
        MatcherAssert.assertThat("exactly one local signing", fake.presignCalls(), new IsEqual<>(1));
    }

    @Test
    void unknownKeyResolvesButNeverPresigns(@TempDir final Path tmp) {
        // resolve() answers "can this backend presign at all" (a
        // CachedBlobStorage-wrapped backend always can, independent of any
        // one key) -- the per-key durability gate is presignIfDurable()'s
        // job, mirroring the PENDING_WRITE case below. An unknown key (never
        // fetched, so the index has never seen it) must never presign.
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = PresignResolverTest.writeThroughStorage(fake, tmp);
        final Optional<PresignResolver.Target> target = PresignResolver.resolve(storage, new Key.From("unknown.jar"));
        MatcherAssert.assertThat(target.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(target.get().durablyPresent(), new IsEqual<>(false));
        MatcherAssert.assertThat(target.get().presignIfDurable(600L).isPresent(), new IsEqual<>(false));
        MatcherAssert.assertThat(fake.presignCalls(), new IsEqual<>(0));
    }

    @Test
    void pendingWriteKeyResolvesButNeverPresigns(@TempDir final Path tmp) throws Exception {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch neverReleases = new CountDownLatch(1);
        fake.gatePut(entered, neverReleases);
        final CachedBlobStorage storage = new CachedBlobStorage(
            fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL, false,
            CachedBlobStorage.WriteBackConfig.defaults(), CachedBlobStorage.EvictionConfig.defaults()
        );
        final Key key = new Key.From("pending.jar");
        // Write-back save() returns once disk-durable; the upload is stuck
        // mid-flight (gated), so the entry stays PENDING_WRITE.
        storage.save(key, new Content.From("x".getBytes(StandardCharsets.UTF_8))).join();
        MatcherAssert.assertThat(entered.await(10, TimeUnit.SECONDS), new IsEqual<>(true));

        final Optional<PresignResolver.Target> target = PresignResolver.resolve(storage, key);
        MatcherAssert.assertThat("a PENDING_WRITE key still resolves a target", target.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "but must never be reported durably present", target.get().durablyPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the mandatory fallback: presignIfDurable must be empty",
            target.get().presignIfDurable(600L).isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat("no signing must have occurred", fake.presignCalls(), new IsEqual<>(0));
    }

    @Test
    void presignNotConfiguredNeverResolves(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        fake.presignConfigured(false);
        fake.seed("no-presign.jar", "x".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storage = PresignResolverTest.writeThroughStorage(fake, tmp);
        final Key key = new Key.From("no-presign.jar");
        storage.value(key).join();

        final Optional<PresignResolver.Target> target = PresignResolver.resolve(storage, key);
        MatcherAssert.assertThat(
            "no Presigner configured on the backend -- must fall back to streaming",
            target.isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void nestedSubStorageComposesTheFullyQualifiedKey(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        fake.seed("repo-prefix/v2/blobs/sha256/abc", "layer-bytes".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage cached = PresignResolverTest.writeThroughStorage(fake, tmp);
        // Mirrors RepositorySlices' real wiring: SubStorage(v2Prefix,
        // SubStorage(repoPrefix, aliasStorage)).
        final Storage repoScoped = new SubStorage(new Key.From("repo-prefix"), cached);
        final Storage v2Scoped = new SubStorage(new Key.From("v2"), repoScoped);
        final Key relativeKey = new Key.From("blobs", "sha256", "abc");
        // Populate the index at the FULL key the base storage actually sees.
        cached.value(new Key.From("repo-prefix", "v2", "blobs", "sha256", "abc")).join();

        final Optional<PresignResolver.Target> target = PresignResolver.resolve(v2Scoped, relativeKey);
        MatcherAssert.assertThat(target.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "the resolved key must be the fully-prefixed one, not the caller-relative one",
            target.get().key(), new IsEqual<>(new Key.From("repo-prefix", "v2", "blobs", "sha256", "abc"))
        );
    }

    private static CachedBlobStorage writeThroughStorage(final RecordingBlobStore fake, final Path tmp) {
        return new CachedBlobStorage(fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL);
    }
}
