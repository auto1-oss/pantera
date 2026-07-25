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
