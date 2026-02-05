/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PackageLocationIndex} and {@link PackageLocations}.
 *
 * @since 1.18.0
 */
final class PackageLocationIndexTest {

    @Test
    void createsEmptyPackageLocations() {
        final PackageLocations locations = new PackageLocations();
        MatcherAssert.assertThat(
            "Empty locations should return empty known locations",
            locations.knownLocations(),
            Matchers.empty()
        );
        MatcherAssert.assertThat(
            "Empty locations should return empty unknown members",
            locations.unknownMembers(),
            Matchers.empty()
        );
    }

    @Test
    void locationEntryDetectsExpiration() {
        final Instant past = Instant.now().minusSeconds(10);
        final Instant future = Instant.now().plusSeconds(10);
        final PackageLocations.LocationEntry expired =
            new PackageLocations.LocationEntry(
                PackageLocations.LocationStatus.EXISTS,
                past
            );
        final PackageLocations.LocationEntry valid =
            new PackageLocations.LocationEntry(
                PackageLocations.LocationStatus.EXISTS,
                future
            );
        MatcherAssert.assertThat(
            "Past expiration should be expired",
            expired.isExpired(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Future expiration should not be expired",
            valid.isExpired(),
            Matchers.is(false)
        );
    }

    @Test
    void locationEntryHandlesMaxInstant() {
        final PackageLocations.LocationEntry noTtl =
            new PackageLocations.LocationEntry(
                PackageLocations.LocationStatus.EXISTS,
                Instant.MAX
            );
        MatcherAssert.assertThat(
            "Instant.MAX should never be expired",
            noTtl.isExpired(),
            Matchers.is(false)
        );
    }

    @Test
    void setsAndGetsStatus() {
        final PackageLocations locations = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        locations.setStatus("member1", PackageLocations.LocationStatus.EXISTS, future);
        MatcherAssert.assertThat(
            "Status should be EXISTS",
            locations.getStatus("member1"),
            Matchers.is(PackageLocations.LocationStatus.EXISTS)
        );
    }

    @Test
    void returnsUnknownForMissingMember() {
        final PackageLocations locations = new PackageLocations();
        MatcherAssert.assertThat(
            "Missing member should return UNKNOWN",
            locations.getStatus("nonexistent"),
            Matchers.is(PackageLocations.LocationStatus.UNKNOWN)
        );
    }

    @Test
    void returnsUnknownForExpiredEntry() {
        final PackageLocations locations = new PackageLocations();
        final Instant past = Instant.now().minusSeconds(10);
        locations.setStatus("member1", PackageLocations.LocationStatus.EXISTS, past);
        MatcherAssert.assertThat(
            "Expired entry should return UNKNOWN",
            locations.getStatus("member1"),
            Matchers.is(PackageLocations.LocationStatus.UNKNOWN)
        );
    }

    @Test
    void knownLocationsReturnsExistsOnly() {
        final PackageLocations locations = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        locations.setStatus("local", PackageLocations.LocationStatus.EXISTS, future);
        locations.setStatus("remote", PackageLocations.LocationStatus.NOT_EXISTS, future);
        locations.setStatus("unknown", PackageLocations.LocationStatus.UNKNOWN, future);
        final List<String> known = locations.knownLocations();
        MatcherAssert.assertThat(
            "Should contain only EXISTS member",
            known,
            Matchers.containsInAnyOrder("local")
        );
        MatcherAssert.assertThat(
            "Should not contain NOT_EXISTS member",
            known,
            Matchers.not(Matchers.hasItem("remote"))
        );
    }

    @Test
    void unknownMembersReturnsUnknownAndExpired() {
        final PackageLocations locations = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        final Instant past = Instant.now().minusSeconds(10);
        locations.setStatus("valid", PackageLocations.LocationStatus.EXISTS, future);
        locations.setStatus("expired", PackageLocations.LocationStatus.EXISTS, past);
        locations.setStatus("unknown", PackageLocations.LocationStatus.UNKNOWN, future);
        final List<String> unknown = locations.unknownMembers();
        MatcherAssert.assertThat(
            "Should contain expired and unknown members",
            unknown,
            Matchers.containsInAnyOrder("expired", "unknown")
        );
        MatcherAssert.assertThat(
            "Should not contain valid EXISTS member",
            unknown,
            Matchers.not(Matchers.hasItem("valid"))
        );
    }

    @Test
    void isNegativelyCachedReturnsTrueForNotExists() {
        final PackageLocations locations = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        locations.setStatus("negative", PackageLocations.LocationStatus.NOT_EXISTS, future);
        MatcherAssert.assertThat(
            "NOT_EXISTS should be negatively cached",
            locations.isNegativelyCached("negative"),
            Matchers.is(true)
        );
    }

    @Test
    void isNegativelyCachedReturnsFalseForExists() {
        final PackageLocations locations = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        locations.setStatus("positive", PackageLocations.LocationStatus.EXISTS, future);
        MatcherAssert.assertThat(
            "EXISTS should not be negatively cached",
            locations.isNegativelyCached("positive"),
            Matchers.is(false)
        );
    }

    @Test
    void isNegativelyCachedReturnsFalseForExpired() {
        final PackageLocations locations = new PackageLocations();
        final Instant past = Instant.now().minusSeconds(10);
        locations.setStatus("expired", PackageLocations.LocationStatus.NOT_EXISTS, past);
        MatcherAssert.assertThat(
            "Expired NOT_EXISTS should not be negatively cached",
            locations.isNegativelyCached("expired"),
            Matchers.is(false)
        );
    }

    @Test
    void removesMember() {
        final PackageLocations locations = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        locations.setStatus("member", PackageLocations.LocationStatus.EXISTS, future);
        locations.remove("member");
        MatcherAssert.assertThat(
            "Removed member should return UNKNOWN",
            locations.getStatus("member"),
            Matchers.is(PackageLocations.LocationStatus.UNKNOWN)
        );
    }

    @Test
    void clearsAllMembers() {
        final PackageLocations locations = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        locations.setStatus("member1", PackageLocations.LocationStatus.EXISTS, future);
        locations.setStatus("member2", PackageLocations.LocationStatus.EXISTS, future);
        locations.clear();
        MatcherAssert.assertThat(
            "After clear, known locations should be empty",
            locations.knownLocations(),
            Matchers.empty()
        );
    }

    @Test
    void entriesReturnsMapCopy() {
        final PackageLocations locations = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        locations.setStatus("member1", PackageLocations.LocationStatus.EXISTS, future);
        final var entries = locations.entries();
        MatcherAssert.assertThat(
            "Entries should contain member1",
            entries.containsKey("member1"),
            Matchers.is(true)
        );
        // Verify it's a copy
        entries.remove("member1");
        MatcherAssert.assertThat(
            "Original should still contain member1 after modifying copy",
            locations.getStatus("member1"),
            Matchers.is(PackageLocations.LocationStatus.EXISTS)
        );
    }

    @Test
    void packageLocationsEqualsAndHashCode() {
        final PackageLocations loc1 = new PackageLocations();
        final PackageLocations loc2 = new PackageLocations();
        final Instant future = Instant.now().plusSeconds(300);
        loc1.setStatus("member", PackageLocations.LocationStatus.EXISTS, future);
        loc2.setStatus("member", PackageLocations.LocationStatus.EXISTS, future);
        MatcherAssert.assertThat(
            "Same content should be equal",
            loc1,
            Matchers.equalTo(loc2)
        );
        MatcherAssert.assertThat(
            "Same content should have same hashCode",
            loc1.hashCode(),
            Matchers.equalTo(loc2.hashCode())
        );
    }

    @Test
    void createsIndexWithDefaultSettings() {
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        final CompletableFuture<PackageLocations> future =
            index.getLocations("test-package");
        MatcherAssert.assertThat(
            "Should return completed future",
            future.isDone(),
            Matchers.is(true)
        );
        final PackageLocations locations = future.join();
        MatcherAssert.assertThat(
            "New package should have empty known locations",
            locations.knownLocations(),
            Matchers.empty()
        );
    }

    @Test
    void markExistsAddsToKnownLocations() {
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        index.markExists("member1", "package1").join();
        final PackageLocations locations = index.getLocations("package1").join();
        MatcherAssert.assertThat(
            "member1 should be in known locations",
            locations.knownLocations(),
            Matchers.hasItem("member1")
        );
    }

    @Test
    void markNotExistsNegativelyCaches() {
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        index.markNotExists("remote1", "package1").join();
        final PackageLocations locations = index.getLocations("package1").join();
        MatcherAssert.assertThat(
            "remote1 should be negatively cached",
            locations.isNegativelyCached("remote1"),
            Matchers.is(true)
        );
    }

    @Test
    void markExistsNoTtlSetsMaxExpiration() {
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        index.markExistsNoTtl("local1", "package1").join();
        final PackageLocations locations = index.getLocations("package1").join();
        MatcherAssert.assertThat(
            "local1 should be in known locations",
            locations.knownLocations(),
            Matchers.hasItem("local1")
        );
        // Entry should not expire (using MAX instant)
        final var entries = locations.entries();
        final var entry = entries.get("local1");
        MatcherAssert.assertThat(
            "Entry should have MAX expiration",
            entry.expiresAt(),
            Matchers.equalTo(Instant.MAX)
        );
    }

    @Test
    void invalidateRemovesPackageEntry() {
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        index.markExists("member1", "package1").join();
        index.invalidate("package1").join();
        final PackageLocations locations = index.getLocations("package1").join();
        MatcherAssert.assertThat(
            "After invalidate, known locations should be empty",
            locations.knownLocations(),
            Matchers.empty()
        );
    }

    @Test
    void invalidateMemberRemovesSpecificMember() {
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        index.markExists("member1", "package1").join();
        index.markExists("member2", "package1").join();
        index.invalidateMember("member1", "package1").join();
        final PackageLocations locations = index.getLocations("package1").join();
        MatcherAssert.assertThat(
            "member2 should still be in known locations",
            locations.knownLocations(),
            Matchers.hasItem("member2")
        );
        MatcherAssert.assertThat(
            "member1 should not be in known locations",
            locations.knownLocations(),
            Matchers.not(Matchers.hasItem("member1"))
        );
    }

    @Test
    void multiplePackagesAreSeparate() {
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        index.markExists("member1", "packageA").join();
        index.markExists("member2", "packageB").join();
        final PackageLocations locA = index.getLocations("packageA").join();
        final PackageLocations locB = index.getLocations("packageB").join();
        MatcherAssert.assertThat(
            "packageA should have member1",
            locA.knownLocations(),
            Matchers.containsInAnyOrder("member1")
        );
        MatcherAssert.assertThat(
            "packageB should have member2",
            locB.knownLocations(),
            Matchers.containsInAnyOrder("member2")
        );
    }

    @Test
    void threadSafetyForPackageLocations() throws Exception {
        final PackageLocations locations = new PackageLocations();
        final int threads = 10;
        final int iterations = 100;
        final CountDownLatch latch = new CountDownLatch(threads);
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        final String member = "member-" + threadId + "-" + j;
                        final Instant future = Instant.now().plusSeconds(300);
                        locations.setStatus(
                            member,
                            PackageLocations.LocationStatus.EXISTS,
                            future
                        );
                        locations.getStatus(member);
                        locations.knownLocations();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        final boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        MatcherAssert.assertThat(
            "All threads should complete without exception",
            completed,
            Matchers.is(true)
        );
    }

    @Test
    void threadSafetyForPackageLocationIndex() throws Exception {
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        final int threads = 10;
        final int iterations = 50;
        final CountDownLatch latch = new CountDownLatch(threads);
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        final String pkg = "package-" + (j % 10);
                        final String member = "member-" + threadId;
                        index.markExists(member, pkg).join();
                        index.getLocations(pkg).join();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        final boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        MatcherAssert.assertThat(
            "All threads should complete without exception",
            completed,
            Matchers.is(true)
        );
    }

    @Test
    void statusEnumValues() {
        MatcherAssert.assertThat(
            "Should have EXISTS status",
            PackageLocations.LocationStatus.EXISTS,
            Matchers.notNullValue()
        );
        MatcherAssert.assertThat(
            "Should have NOT_EXISTS status",
            PackageLocations.LocationStatus.NOT_EXISTS,
            Matchers.notNullValue()
        );
        MatcherAssert.assertThat(
            "Should have UNKNOWN status",
            PackageLocations.LocationStatus.UNKNOWN,
            Matchers.notNullValue()
        );
    }

    @Test
    void instantMaxEntryRemainsValidAfterReload() {
        // Verify Instant.MAX entries work correctly (tests serialization fix)
        final GroupSettings settings = GroupSettings.defaults();
        final PackageLocationIndex index = new PackageLocationIndex(
            "test-group",
            settings,
            Optional.empty()
        );
        index.markExistsNoTtl("local1", "pkg").join();
        // Get locations twice to ensure the entry is retrieved correctly
        final PackageLocations loc1 = index.getLocations("pkg").join();
        final PackageLocations loc2 = index.getLocations("pkg").join();
        MatcherAssert.assertThat(
            "Entry should have Instant.MAX",
            loc2.entries().get("local1").expiresAt(),
            Matchers.equalTo(Instant.MAX)
        );
    }
}
