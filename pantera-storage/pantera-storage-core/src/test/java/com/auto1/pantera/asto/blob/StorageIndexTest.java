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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link StorageIndex}: pure in-memory lookups (no disk, no blob
 * store), negative-entry TTL expiry, prefix listing, and boot rebuild from a
 * disk scan of {@code .meta} sidecars.
 */
final class StorageIndexTest {

    @Test
    void unknownKeyIsEmpty() {
        final StorageIndex index = new StorageIndex();
        MatcherAssert.assertThat(
            "an index that has never seen this key must report it unknown",
            index.knownEntry(new Key.From("never-seen")).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void putPresentIsImmediatelyKnownAndFresh() {
        final StorageIndex index = new StorageIndex();
        final Key key = new Key.From("a", "b.jar");
        index.putPresent(key, 42L, "etag-1", "digest-1", true);
        final Optional<StorageIndex.Entry> known = index.knownEntry(key);
        MatcherAssert.assertThat("must be known immediately after putPresent", known.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(known.get().size(), new IsEqual<>(42L));
        MatcherAssert.assertThat(known.get().etag(), new IsEqual<>("etag-1"));
        MatcherAssert.assertThat(known.get().digest(), new IsEqual<>("digest-1"));
        MatcherAssert.assertThat(known.get().presentOnDisk(), new IsEqual<>(true));
        MatcherAssert.assertThat(known.get().negative(), new IsEqual<>(false));
        MatcherAssert.assertThat(
            "a fresh positive entry must qualify for the disk-served hit path",
            index.freshEntry(key, Duration.ofMinutes(5)).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void freshEntryExpiresOutsideTtlWithoutWallClockSleep() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        final StorageIndex index = new StorageIndex(clock);
        final Key key = new Key.From("expiring");
        index.putPresent(key, 1L, null, null, true);
        MatcherAssert.assertThat(
            "still fresh right after write",
            index.freshEntry(key, Duration.ofSeconds(30)).isPresent(),
            new IsEqual<>(true)
        );
        clock.advance(Duration.ofSeconds(31));
        MatcherAssert.assertThat(
            "must no longer be considered fresh once the TTL has elapsed",
            index.freshEntry(key, Duration.ofSeconds(30)).isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a stale positive entry is still KNOWN (exists()/metadata() stay answerable) -- only the disk-hit path expires",
            index.knownEntry(key).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void negativeEntryExpiresAndIsForgotten() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        final StorageIndex index = new StorageIndex(clock);
        final Key key = new Key.From("missing-upstream");
        index.putNegative(key, Duration.ofSeconds(10));
        final Optional<StorageIndex.Entry> known = index.knownEntry(key);
        MatcherAssert.assertThat("a fresh negative entry is known", known.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(known.get().negative(), new IsEqual<>(true));
        clock.advance(Duration.ofSeconds(11));
        MatcherAssert.assertThat(
            "an expired negative entry must be forgotten so the caller re-resolves it",
            index.knownEntry(key).isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void removeClearsAnyEntry() {
        final StorageIndex index = new StorageIndex();
        final Key key = new Key.From("removable");
        index.putPresent(key, 1L, null, null, true);
        index.remove(key);
        MatcherAssert.assertThat(index.knownEntry(key).isPresent(), new IsEqual<>(false));
    }

    @Test
    void listPrefixRecursiveReturnsAllMatchingKeys() {
        final StorageIndex index = new StorageIndex();
        index.putPresent(new Key.From("com", "a", "1.jar"), 1L, null, null, true);
        index.putPresent(new Key.From("com", "b", "2.jar"), 1L, null, null, true);
        index.putPresent(new Key.From("org", "c", "3.jar"), 1L, null, null, true);
        final Collection<Key> matches = index.listPrefix(new Key.From("com"));
        MatcherAssert.assertThat(matches.size(), new IsEqual<>(2));
    }

    @Test
    void listPrefixDelimitedSplitsFilesAndDirectories() {
        final StorageIndex index = new StorageIndex();
        index.putPresent(new Key.From("com", "README.md"), 1L, null, null, true);
        index.putPresent(new Key.From("com", "google", "guava", "1.0", "guava-1.0.jar"), 1L, null, null, true);
        index.putPresent(new Key.From("com", "apache", "commons", "1.0", "commons-1.0.jar"), 1L, null, null, true);
        final ListResult result = index.listPrefix(new Key.From("com"), "/");
        MatcherAssert.assertThat(
            "one file directly under com/",
            result.files().stream().map(Key::string).toList(),
            new IsEqual<>(List.of("com/README.md"))
        );
        MatcherAssert.assertThat(
            "two subdirectories directly under com/",
            result.directories().stream().map(Key::string).sorted().toList(),
            new IsEqual<>(List.of("com/apache", "com/google"))
        );
    }

    @Test
    void negativeEntryIsExcludedFromListing() {
        final StorageIndex index = new StorageIndex();
        index.putPresent(new Key.From("present.jar"), 1L, null, null, true);
        index.putNegative(new Key.From("missing.jar"), Duration.ofMinutes(1));
        final Collection<Key> matches = index.listPrefix(Key.ROOT);
        MatcherAssert.assertThat(
            matches.stream().map(Key::string).toList(),
            new IsEqual<>(List.of("present.jar"))
        );
    }

    @Test
    void rebuildFromDiskHydratesEntryWithSidecarMetadata(@TempDir final Path tmp) throws IOException {
        final Path dataFile = tmp.resolve("a").resolve("lib-1.0.jar");
        Files.createDirectories(dataFile.getParent());
        Files.write(dataFile, "artifact-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StorageIndex.Sidecar.write(
            Path.of(dataFile + StorageIndex.SIDECAR_SUFFIX),
            StorageIndex.Entry.present(14L, "sidecar-etag", "sidecar-digest", 1234L, true)
        );
        final StorageIndex index = new StorageIndex();
        index.rebuildFromDisk(tmp);
        final Optional<StorageIndex.Entry> rebuilt = index.knownEntry(new Key.From("a", "lib-1.0.jar"));
        MatcherAssert.assertThat("rebuilt entry must be known from the disk scan alone", rebuilt.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(rebuilt.get().size(), new IsEqual<>(14L));
        MatcherAssert.assertThat(rebuilt.get().etag(), new IsEqual<>("sidecar-etag"));
        MatcherAssert.assertThat(rebuilt.get().digest(), new IsEqual<>("sidecar-digest"));
        MatcherAssert.assertThat(rebuilt.get().presentOnDisk(), new IsEqual<>(true));
    }

    @Test
    void rebuildFromDiskFallsBackToFilesystemAttributesWithoutSidecar(@TempDir final Path tmp) throws IOException {
        final Path dataFile = tmp.resolve("no-sidecar.txt");
        final byte[] content = "twelve bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(dataFile, content);
        final StorageIndex index = new StorageIndex();
        index.rebuildFromDisk(tmp);
        final Optional<StorageIndex.Entry> rebuilt = index.knownEntry(new Key.From("no-sidecar.txt"));
        MatcherAssert.assertThat(rebuilt.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(rebuilt.get().size(), new IsEqual<>((long) content.length));
        MatcherAssert.assertThat(rebuilt.get().presentOnDisk(), new IsEqual<>(true));
    }

    @Test
    void rebuildFromDiskIgnoresStagingAndSidecarFiles(@TempDir final Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve(".tmp"));
        Files.write(tmp.resolve(".tmp").resolve("in-flight-upload"), "partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final Path realFile = tmp.resolve("real.jar");
        Files.write(realFile, "real".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final StorageIndex index = new StorageIndex();
        index.rebuildFromDisk(tmp);
        MatcherAssert.assertThat(
            "only the real data file becomes an entry -- staging temp files must not",
            index.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(index.knownEntry(new Key.From("real.jar")).isPresent(), new IsEqual<>(true));
    }

    @Test
    void rebuildFromDiskOnMissingRootIsANoOp(@TempDir final Path tmp) {
        final StorageIndex index = new StorageIndex();
        index.rebuildFromDisk(tmp.resolve("does-not-exist"));
        MatcherAssert.assertThat(index.size(), new IsEqual<>(0));
    }

    @Test
    void putPendingWriteIsPresentOnDiskButNotConfirmed() {
        final StorageIndex index = new StorageIndex();
        final Key key = new Key.From("uploading.jar");
        index.putPendingWrite(key, 7L, null, "digest-pending");
        final Optional<StorageIndex.Entry> known = index.knownEntry(key);
        MatcherAssert.assertThat("a PENDING_WRITE entry must be known", known.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(known.get().pendingUpload(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "PENDING_WRITE bytes are durable on local disk -- presentOnDisk must be true",
            known.get().presentOnDisk(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat("PENDING_WRITE is not a negative/ABSENT entry", known.get().negative(), new IsEqual<>(false));
        MatcherAssert.assertThat(known.get().size(), new IsEqual<>(7L));
        MatcherAssert.assertThat(known.get().digest(), new IsEqual<>("digest-pending"));
    }

    @Test
    void pendingWriteNeverExpiresOutOfFreshnessRegardlessOfTtl() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        final StorageIndex index = new StorageIndex(clock);
        final Key key = new Key.From("still-pending.jar");
        index.putPendingWrite(key, 1L, null, "d");
        clock.advance(Duration.ofDays(1));
        MatcherAssert.assertThat(
            "a PENDING_WRITE entry is locally authoritative until confirmed PRESENT -- "
                + "it must stay disk-servable no matter how long the upload takes",
            index.freshEntry(key, Duration.ofSeconds(1)).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void putPresentAfterPendingWriteFlipsToConfirmedAndClearsPendingUpload() {
        final StorageIndex index = new StorageIndex();
        final Key key = new Key.From("confirmed.jar");
        index.putPendingWrite(key, 5L, null, "d");
        index.putPresent(key, 5L, "etag", "d", true);
        final Optional<StorageIndex.Entry> known = index.knownEntry(key);
        MatcherAssert.assertThat(known.get().pendingUpload(), new IsEqual<>(false));
        MatcherAssert.assertThat(known.get().etag(), new IsEqual<>("etag"));
    }

    @Test
    void pendingWriteKeysEnumeratesExactlyThePendingEntries() {
        final StorageIndex index = new StorageIndex();
        index.putPresent(new Key.From("confirmed.jar"), 1L, null, null, true);
        index.putPendingWrite(new Key.From("pending-1.jar"), 1L, null, null);
        index.putPendingWrite(new Key.From("pending-2.jar"), 1L, null, null);
        index.putNegative(new Key.From("missing.jar"), Duration.ofMinutes(1));
        final Collection<Key> pending = index.pendingWriteKeys();
        MatcherAssert.assertThat(
            pending.stream().map(Key::string).sorted().toList(),
            new IsEqual<>(List.of("pending-1.jar", "pending-2.jar"))
        );
    }

    @Test
    void oldestPendingWriteAgeMillisIsZeroWhenNothingIsPending() {
        final StorageIndex index = new StorageIndex();
        index.putPresent(new Key.From("confirmed.jar"), 1L, null, null, true);
        MatcherAssert.assertThat(index.oldestPendingWriteAgeMillis(System.currentTimeMillis()), new IsEqual<>(0L));
    }

    @Test
    void oldestPendingWriteAgeMillisReturnsTheLongestOutstandingPendingWritesAge() {
        // WS1.6 (spec sect 3.G): caller-supplied "now" keeps this test free
        // of wall-clock coupling (CLAUDE.md doctrine) -- the index's OWN
        // clock (injected here, deterministic) stamps each entry's
        // lastModifiedEpochMilli at the moment it was written.
        final MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L));
        final StorageIndex index = new StorageIndex(clock);
        index.putPendingWrite(new Key.From("older.jar"), 1L, null, null);
        clock.advance(Duration.ofMillis(500));
        index.putPendingWrite(new Key.From("newer.jar"), 1L, null, null);

        final long now = clock.millis() + 2_500L;
        MatcherAssert.assertThat(
            "the OLDEST still-pending entry (older.jar, written at 1000) drives the age, not the newest",
            index.oldestPendingWriteAgeMillis(now), new IsEqual<>(3_000L)
        );
    }

    @Test
    void oldestPendingWriteAgeMillisIgnoresAPendingEntryOnceConfirmedPresent() {
        final MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L));
        final StorageIndex index = new StorageIndex(clock);
        final Key key = new Key.From("confirmed-later.jar");
        index.putPendingWrite(key, 1L, null, null);
        clock.advance(Duration.ofMillis(10_000L));
        index.putPresent(key, 1L, null, null, true);
        MatcherAssert.assertThat(index.oldestPendingWriteAgeMillis(clock.millis()), new IsEqual<>(0L));
    }

    @Test
    void rebuildFromDiskRecoversPendingWriteStateFromSidecar(@TempDir final Path tmp) throws IOException {
        final Path dataFile = tmp.resolve("uploading.jar");
        Files.write(dataFile, "not-yet-uploaded".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StorageIndex.Sidecar.write(
            Path.of(dataFile + StorageIndex.SIDECAR_SUFFIX),
            StorageIndex.Entry.pendingWrite(16L, null, "pending-digest", 999L)
        );
        final StorageIndex index = new StorageIndex();
        index.rebuildFromDisk(tmp);
        final Optional<StorageIndex.Entry> rebuilt = index.knownEntry(new Key.From("uploading.jar"));
        MatcherAssert.assertThat("PENDING_WRITE must survive a sidecar round-trip", rebuilt.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(rebuilt.get().pendingUpload(), new IsEqual<>(true));
        MatcherAssert.assertThat(rebuilt.get().presentOnDisk(), new IsEqual<>(true));
        MatcherAssert.assertThat(rebuilt.get().digest(), new IsEqual<>("pending-digest"));
        MatcherAssert.assertThat(
            "the boot-replay accessor must surface this key",
            index.pendingWriteKeys().stream().map(Key::string).toList(),
            new IsEqual<>(List.of("uploading.jar"))
        );
    }

    @Test
    void rebuildFromDiskWithoutPendingUploadKeyDefaultsToPresent(@TempDir final Path tmp) throws IOException {
        // A sidecar written before WS1.2 has no pendingUpload property at
        // all -- Sidecar.read must default it to false (PRESENT), preserving
        // the pre-WS1.2 meaning of every sidecar already on disk.
        final Path dataFile = tmp.resolve("pre-ws1-2.jar");
        Files.write(dataFile, "old-sidecar-format".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StorageIndex.Sidecar.write(
            Path.of(dataFile + StorageIndex.SIDECAR_SUFFIX),
            StorageIndex.Entry.present(18L, "etag", "digest", 1L, true)
        );
        final StorageIndex index = new StorageIndex();
        index.rebuildFromDisk(tmp);
        final Optional<StorageIndex.Entry> rebuilt = index.knownEntry(new Key.From("pre-ws1-2.jar"));
        MatcherAssert.assertThat(rebuilt.get().pendingUpload(), new IsEqual<>(false));
        MatcherAssert.assertThat(index.pendingWriteKeys().isEmpty(), new IsEqual<>(true));
    }

    // ===== WS1.4 (spec WS1-storage-for-scale.md sect.3.D): lastAccess/hits, =====
    // ===== running disk-byte counter, eviction candidates, sharded rebuild =====

    @Test
    void recordAccessBumpsHitsAndRefreshesLastAccessWithoutWallClockSleep() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        final StorageIndex index = new StorageIndex(clock);
        final Key key = new Key.From("hot.jar");
        index.putPresent(key, 10L, null, null, true);
        MatcherAssert.assertThat("a freshly-written entry starts at 0 hits", index.knownEntry(key).get().hits(), new IsEqual<>(0L));
        clock.advance(Duration.ofSeconds(5));
        index.recordAccess(key);
        final StorageIndex.Entry afterOneHit = index.knownEntry(key).get();
        MatcherAssert.assertThat(afterOneHit.hits(), new IsEqual<>(1L));
        MatcherAssert.assertThat(afterOneHit.lastAccessEpochMilli(), new IsEqual<>(clock.instant().toEpochMilli()));
        clock.advance(Duration.ofSeconds(5));
        index.recordAccess(key);
        final StorageIndex.Entry afterTwoHits = index.knownEntry(key).get();
        MatcherAssert.assertThat("a second access must bump hits again", afterTwoHits.hits(), new IsEqual<>(2L));
        MatcherAssert.assertThat(afterTwoHits.lastAccessEpochMilli(), new IsEqual<>(clock.instant().toEpochMilli()));
    }

    @Test
    void recordAccessOnUnknownKeyIsANoOp() {
        final StorageIndex index = new StorageIndex();
        index.recordAccess(new Key.From("never-written.jar"));
        MatcherAssert.assertThat(
            "recordAccess on a key the index has never seen must not create an entry",
            index.knownEntry(new Key.From("never-written.jar")).isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void diskBytesUsedTracksPutPresentAndRemoveWithoutFilesWalk() {
        final StorageIndex index = new StorageIndex();
        MatcherAssert.assertThat(index.diskBytesUsed(), new IsEqual<>(0L));
        index.putPresent(new Key.From("a.jar"), 100L, null, null, true);
        MatcherAssert.assertThat(index.diskBytesUsed(), new IsEqual<>(100L));
        index.putPresent(new Key.From("b.jar"), 250L, null, null, true);
        MatcherAssert.assertThat(index.diskBytesUsed(), new IsEqual<>(350L));
        index.remove(new Key.From("a.jar"));
        MatcherAssert.assertThat(index.diskBytesUsed(), new IsEqual<>(250L));
        index.remove(new Key.From("b.jar"));
        MatcherAssert.assertThat(index.diskBytesUsed(), new IsEqual<>(0L));
    }

    @Test
    void diskBytesUsedReflectsOverwriteDeltaNotDoubleCounting() {
        final StorageIndex index = new StorageIndex();
        final Key key = new Key.From("resized.jar");
        index.putPresent(key, 100L, null, null, true);
        MatcherAssert.assertThat(index.diskBytesUsed(), new IsEqual<>(100L));
        index.putPresent(key, 40L, null, null, true);
        MatcherAssert.assertThat(
            "overwriting an existing entry must replace its byte contribution, not add to it",
            index.diskBytesUsed(), new IsEqual<>(40L)
        );
    }

    @Test
    void diskBytesUsedIgnoresNegativeAndMetadataOnlyEntries() {
        final StorageIndex index = new StorageIndex();
        index.putNegative(new Key.From("missing.jar"), Duration.ofMinutes(1));
        index.putPresent(new Key.From("metadata-only.jar"), 999L, null, null, false);
        MatcherAssert.assertThat(
            "a negative entry and a metadata-only (not presentOnDisk) entry occupy zero disk bytes",
            index.diskBytesUsed(), new IsEqual<>(0L)
        );
    }

    @Test
    void diskBytesUsedTracksPendingWriteAndTransitionToPresent() {
        final StorageIndex index = new StorageIndex();
        final Key key = new Key.From("wb.jar");
        index.putPendingWrite(key, 64L, null, "d");
        MatcherAssert.assertThat(index.diskBytesUsed(), new IsEqual<>(64L));
        index.putPresent(key, 64L, "etag", "d", true);
        MatcherAssert.assertThat(
            "confirming PENDING_WRITE as PRESENT must not double-count the same bytes",
            index.diskBytesUsed(), new IsEqual<>(64L)
        );
    }

    @Test
    void evictionCandidatesExcludesPendingUploadNegativeAndMetadataOnlyEntries() {
        final StorageIndex index = new StorageIndex();
        index.putPresent(new Key.From("evictable.jar"), 1L, null, null, true);
        index.putPendingWrite(new Key.From("pending.jar"), 1L, null, null);
        index.putNegative(new Key.From("missing.jar"), Duration.ofMinutes(1));
        index.putPresent(new Key.From("metadata-only.jar"), 1L, null, null, false);
        final List<Map.Entry<Key, StorageIndex.Entry>> candidates = index.evictionCandidates();
        MatcherAssert.assertThat(
            "only the plain PRESENT, present-on-disk entry is eviction-eligible",
            candidates.stream().map(candidate -> candidate.getKey().string()).toList(),
            new IsEqual<>(List.of("evictable.jar"))
        );
    }

    @Test
    void rebuildFromDiskWithResolverUsesTheSuppliedKeyMapping(@TempDir final Path tmp) throws IOException {
        // A minimal stand-in for WS1.4's sharded layout (CacheKeyShard lives
        // in CachedBlobStorage's production wiring; this proves the generic
        // resolver plumbing in isolation): the on-disk file name has no
        // relation to the logical key at all, only the resolver does.
        final Path dataFile = tmp.resolve("opaque-blob-name");
        Files.write(dataFile, "sharded-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final StorageIndex index = new StorageIndex();
        index.rebuildFromDisk(tmp, path -> Optional.of(new Key.From("logical", "key.jar")));
        MatcherAssert.assertThat(
            "the resolver -- not the on-disk file name -- determines the recovered key",
            index.knownEntry(new Key.From("logical", "key.jar")).isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(index.knownEntry(new Key.From("opaque-blob-name")).isPresent(), new IsEqual<>(false));
    }

    @Test
    void rebuildFromDiskWithResolverSkipsFilesTheResolverCannotMap(@TempDir final Path tmp) throws IOException {
        Files.write(tmp.resolve("unmappable"), "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final StorageIndex index = new StorageIndex();
        index.rebuildFromDisk(tmp, path -> Optional.empty());
        MatcherAssert.assertThat(
            "a file the resolver cannot map to a key must be skipped, not crash the rebuild",
            index.size(), new IsEqual<>(0)
        );
    }

    /**
     * Deterministically advanceable {@link Clock} so TTL-expiry tests prove
     * semantics via explicit time control instead of {@code Thread.sleep}
     * (CLAUDE.md: never assert wall-clock).
     */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(final Instant start) {
            this.now = start;
        }

        void advance(final Duration delta) {
            this.now = this.now.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
