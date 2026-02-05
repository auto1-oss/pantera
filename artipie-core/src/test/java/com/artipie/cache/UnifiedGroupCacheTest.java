/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UnifiedGroupCache}.
 *
 * @since 1.18.0
 */
final class UnifiedGroupCacheTest {

    @Test
    void getMetadataReturnsCachedResultOnHit() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Pre-populate cache
        final byte[] metadata = "cached metadata".getBytes(StandardCharsets.UTF_8);
        cache.cacheMetadata("npm", "lodash", metadata).join();
        // Create member fetchers that should NOT be called
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final List<UnifiedGroupCache.MemberFetcher> fetchers = Arrays.asList(
            new TestMemberFetcher("member1", () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of("member1 data".getBytes(StandardCharsets.UTF_8))
                );
            })
        );
        // Get should return cached result without fetching
        final Optional<byte[]> result = cache.getMetadata(
            "npm",
            "lodash",
            fetchers,
            responses -> "merged".getBytes(StandardCharsets.UTF_8)
        ).join();
        MatcherAssert.assertThat(
            "Should return cached result",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Cached metadata should match",
            result.get(),
            Matchers.equalTo(metadata)
        );
        MatcherAssert.assertThat(
            "Member fetchers should not be called on cache hit",
            fetchCount.get(),
            Matchers.is(0)
        );
    }

    @Test
    void getMetadataFetchesAndMergesOnCacheMiss() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Create member fetchers that return data
        final List<UnifiedGroupCache.MemberFetcher> fetchers = Arrays.asList(
            new TestMemberFetcher("member1", () -> CompletableFuture.completedFuture(
                Optional.of("data1".getBytes(StandardCharsets.UTF_8))
            )),
            new TestMemberFetcher("member2", () -> CompletableFuture.completedFuture(
                Optional.of("data2".getBytes(StandardCharsets.UTF_8))
            ))
        );
        // Create merger that verifies inputs and returns merged result
        final AtomicInteger mergeCount = new AtomicInteger(0);
        final MetadataMerger merger = responses -> {
            mergeCount.incrementAndGet();
            MatcherAssert.assertThat(
                "Merger should receive responses from both members",
                responses.size(),
                Matchers.is(2)
            );
            MatcherAssert.assertThat(
                "Merger should receive member1 data",
                responses.containsKey("member1"),
                Matchers.is(true)
            );
            MatcherAssert.assertThat(
                "Merger should receive member2 data",
                responses.containsKey("member2"),
                Matchers.is(true)
            );
            return "merged result".getBytes(StandardCharsets.UTF_8);
        };
        final Optional<byte[]> result = cache.getMetadata(
            "npm",
            "newpackage",
            fetchers,
            merger
        ).join();
        MatcherAssert.assertThat(
            "Should return merged result",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Result should be merged data",
            new String(result.get(), StandardCharsets.UTF_8),
            Matchers.is("merged result")
        );
        MatcherAssert.assertThat(
            "Merger should be called once",
            mergeCount.get(),
            Matchers.is(1)
        );
        // Verify result is cached for next call
        final AtomicInteger fetchCount2 = new AtomicInteger(0);
        final List<UnifiedGroupCache.MemberFetcher> fetchers2 = Arrays.asList(
            new TestMemberFetcher("member1", () -> {
                fetchCount2.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            })
        );
        cache.getMetadata("npm", "newpackage", fetchers2, responses -> new byte[0]).join();
        MatcherAssert.assertThat(
            "Second call should use cache, not fetch",
            fetchCount2.get(),
            Matchers.is(0)
        );
    }

    @Test
    void getMetadataReturnsEmptyWhenNoMembersHavePackage() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Create member fetchers that return empty
        final List<UnifiedGroupCache.MemberFetcher> fetchers = Arrays.asList(
            new TestMemberFetcher("member1", () -> CompletableFuture.completedFuture(
                Optional.empty()
            )),
            new TestMemberFetcher("member2", () -> CompletableFuture.completedFuture(
                Optional.empty()
            ))
        );
        final Optional<byte[]> result = cache.getMetadata(
            "npm",
            "nonexistent",
            fetchers,
            responses -> "should not be called".getBytes(StandardCharsets.UTF_8)
        ).join();
        MatcherAssert.assertThat(
            "Should return empty when no members have package",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void onLocalPublishUpdatesIndexAndInvalidatesCache() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Pre-populate cache
        final byte[] oldMetadata = "old metadata".getBytes(StandardCharsets.UTF_8);
        cache.cacheMetadata("npm", "mypackage", oldMetadata).join();
        // Trigger local publish event
        cache.onLocalPublish("local-repo", "mypackage").join();
        // Verify index is updated
        final PackageLocations locations = cache.getLocations("mypackage").join();
        MatcherAssert.assertThat(
            "Local repo should be marked as EXISTS in index",
            locations.knownLocations(),
            Matchers.hasItem("local-repo")
        );
        // Verify cache is invalidated - need to fetch again
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final List<UnifiedGroupCache.MemberFetcher> fetchers = Arrays.asList(
            new TestMemberFetcher("local-repo", () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of("new metadata".getBytes(StandardCharsets.UTF_8))
                );
            })
        );
        cache.getMetadata(
            "npm",
            "mypackage",
            fetchers,
            responses -> responses.values().iterator().next()
        ).join();
        MatcherAssert.assertThat(
            "Cache should be invalidated, requiring fetch",
            fetchCount.get(),
            Matchers.is(1)
        );
    }

    @Test
    void onLocalDeleteRemovesFromIndexAndInvalidatesCache() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Setup: mark member as exists and cache metadata
        cache.recordMemberHit("local-repo", "mypackage").join();
        cache.cacheMetadata("npm", "mypackage", "cached".getBytes(StandardCharsets.UTF_8)).join();
        // Verify member is in index before delete
        PackageLocations locationsBefore = cache.getLocations("mypackage").join();
        MatcherAssert.assertThat(
            "Member should exist in index before delete",
            locationsBefore.knownLocations(),
            Matchers.hasItem("local-repo")
        );
        // Trigger local delete event
        cache.onLocalDelete("local-repo", "mypackage").join();
        // Verify member is removed from index
        final PackageLocations locationsAfter = cache.getLocations("mypackage").join();
        MatcherAssert.assertThat(
            "Local repo should be removed from index",
            locationsAfter.knownLocations(),
            Matchers.not(Matchers.hasItem("local-repo"))
        );
        // Verify cache is invalidated
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final List<UnifiedGroupCache.MemberFetcher> fetchers = Arrays.asList(
            new TestMemberFetcher("other-repo", () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of("data".getBytes(StandardCharsets.UTF_8))
                );
            })
        );
        cache.getMetadata(
            "npm",
            "mypackage",
            fetchers,
            responses -> responses.values().iterator().next()
        ).join();
        MatcherAssert.assertThat(
            "Cache should be invalidated, requiring fetch",
            fetchCount.get(),
            Matchers.is(1)
        );
    }

    @Test
    void recordMemberHitUpdatesIndex() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Record hit
        cache.recordMemberHit("remote-repo", "somepackage").join();
        // Verify index is updated
        final PackageLocations locations = cache.getLocations("somepackage").join();
        MatcherAssert.assertThat(
            "Member should be marked as EXISTS",
            locations.knownLocations(),
            Matchers.hasItem("remote-repo")
        );
    }

    @Test
    void recordMemberMissUpdatesIndex() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Record miss
        cache.recordMemberMiss("remote-repo", "somepackage").join();
        // Verify index is updated with negative cache
        final PackageLocations locations = cache.getLocations("somepackage").join();
        MatcherAssert.assertThat(
            "Member should be negatively cached",
            locations.isNegativelyCached("remote-repo"),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Member should not be in known locations",
            locations.knownLocations(),
            Matchers.not(Matchers.hasItem("remote-repo"))
        );
    }

    @Test
    void parallelFetchingBehavior() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Create fetchers with delays to verify parallel execution
        final long startTime = System.currentTimeMillis();
        final AtomicInteger concurrentCount = new AtomicInteger(0);
        final AtomicInteger maxConcurrent = new AtomicInteger(0);
        final List<UnifiedGroupCache.MemberFetcher> fetchers = Arrays.asList(
            new TestMemberFetcher("member1", () -> {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    concurrentCount.decrementAndGet();
                    return Optional.of("data1".getBytes(StandardCharsets.UTF_8));
                });
            }),
            new TestMemberFetcher("member2", () -> {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    concurrentCount.decrementAndGet();
                    return Optional.of("data2".getBytes(StandardCharsets.UTF_8));
                });
            }),
            new TestMemberFetcher("member3", () -> {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    concurrentCount.decrementAndGet();
                    return Optional.of("data3".getBytes(StandardCharsets.UTF_8));
                });
            })
        );
        cache.getMetadata(
            "npm",
            "parallel-test",
            fetchers,
            responses -> "merged".getBytes(StandardCharsets.UTF_8)
        ).join();
        final long elapsed = System.currentTimeMillis() - startTime;
        // If executed sequentially, would take ~300ms. Parallel should be ~100ms
        MatcherAssert.assertThat(
            "Fetches should execute in parallel (elapsed < 250ms)",
            elapsed,
            Matchers.lessThan(250L)
        );
        MatcherAssert.assertThat(
            "Multiple fetchers should run concurrently",
            maxConcurrent.get(),
            Matchers.greaterThan(1)
        );
    }

    @Test
    void getLocationsReturnsPackageLocations() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Setup some locations
        cache.recordMemberHit("member1", "pkg").join();
        cache.recordMemberHit("member2", "pkg").join();
        cache.recordMemberMiss("member3", "pkg").join();
        final PackageLocations locations = cache.getLocations("pkg").join();
        MatcherAssert.assertThat(
            "Should have member1 and member2 as known locations",
            locations.knownLocations(),
            Matchers.containsInAnyOrder("member1", "member2")
        );
        MatcherAssert.assertThat(
            "member3 should be negatively cached",
            locations.isNegativelyCached("member3"),
            Matchers.is(true)
        );
    }

    @Test
    void cacheMetadataPrePopulatesCache() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        final byte[] metadata = "pre-populated".getBytes(StandardCharsets.UTF_8);
        cache.cacheMetadata("maven", "artifact", metadata).join();
        // Verify can retrieve without fetching
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final List<UnifiedGroupCache.MemberFetcher> fetchers = Arrays.asList(
            new TestMemberFetcher("member", () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            })
        );
        final Optional<byte[]> result = cache.getMetadata(
            "maven",
            "artifact",
            fetchers,
            responses -> new byte[0]
        ).join();
        MatcherAssert.assertThat(
            "Should return pre-populated metadata",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Metadata should match pre-populated value",
            result.get(),
            Matchers.equalTo(metadata)
        );
        MatcherAssert.assertThat(
            "Should not call fetchers when cache hit",
            fetchCount.get(),
            Matchers.is(0)
        );
    }

    @Test
    void autoCloseableImplementation() throws Exception {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Should implement AutoCloseable
        MatcherAssert.assertThat(
            "UnifiedGroupCache should implement AutoCloseable",
            cache,
            Matchers.instanceOf(AutoCloseable.class)
        );
        // Close should not throw
        cache.close();
    }

    @Test
    void threadSafetyForConcurrentOperations() throws Exception {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
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
                        final String pkg = "package-" + (j % 5);
                        final String member = "member-" + threadId;
                        // Mix of operations
                        if (j % 3 == 0) {
                            cache.recordMemberHit(member, pkg).join();
                        } else if (j % 3 == 1) {
                            cache.recordMemberMiss(member, pkg).join();
                        } else {
                            cache.getLocations(pkg).join();
                        }
                        if (j % 5 == 0) {
                            cache.cacheMetadata(
                                "npm",
                                pkg,
                                ("data-" + threadId).getBytes(StandardCharsets.UTF_8)
                            ).join();
                        }
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
    void fetchResultsUpdateIndexAutomatically() {
        final GroupSettings settings = GroupSettings.defaults();
        final UnifiedGroupCache cache = new UnifiedGroupCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Create fetchers with mixed results
        final List<UnifiedGroupCache.MemberFetcher> fetchers = Arrays.asList(
            new TestMemberFetcher("hasData", () -> CompletableFuture.completedFuture(
                Optional.of("data".getBytes(StandardCharsets.UTF_8))
            )),
            new TestMemberFetcher("noData", () -> CompletableFuture.completedFuture(
                Optional.empty()
            ))
        );
        cache.getMetadata(
            "npm",
            "indextest",
            fetchers,
            responses -> responses.values().iterator().next()
        ).join();
        // Verify index is updated based on fetch results
        final PackageLocations locations = cache.getLocations("indextest").join();
        MatcherAssert.assertThat(
            "Member with data should be marked EXISTS",
            locations.knownLocations(),
            Matchers.hasItem("hasData")
        );
        MatcherAssert.assertThat(
            "Member without data should be negatively cached",
            locations.isNegativelyCached("noData"),
            Matchers.is(true)
        );
    }

    @Test
    void memberFetcherInterfaceContract() {
        final String memberName = "test-member";
        final byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        final UnifiedGroupCache.MemberFetcher fetcher = new TestMemberFetcher(
            memberName,
            () -> CompletableFuture.completedFuture(Optional.of(data))
        );
        MatcherAssert.assertThat(
            "memberName() should return the member name",
            fetcher.memberName(),
            Matchers.is(memberName)
        );
        final Optional<byte[]> result = fetcher.fetch().join();
        MatcherAssert.assertThat(
            "fetch() should return data",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "fetch() data should match",
            result.get(),
            Matchers.equalTo(data)
        );
    }

    /**
     * Test implementation of MemberFetcher.
     */
    private static final class TestMemberFetcher
        implements UnifiedGroupCache.MemberFetcher {

        /**
         * Member name.
         */
        private final String name;

        /**
         * Fetch supplier.
         */
        private final java.util.function.Supplier<CompletableFuture<Optional<byte[]>>> supplier;

        /**
         * Constructor.
         * @param name Member name
         * @param supplier Fetch supplier
         */
        TestMemberFetcher(
            final String name,
            final java.util.function.Supplier<CompletableFuture<Optional<byte[]>>> supplier
        ) {
            this.name = name;
            this.supplier = supplier;
        }

        @Override
        public String memberName() {
            return this.name;
        }

        @Override
        public CompletableFuture<Optional<byte[]>> fetch() {
            return this.supplier.get();
        }
    }
}
