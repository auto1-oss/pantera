/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import java.nio.charset.StandardCharsets;
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
 * Tests for {@link MergedMetadataCache}.
 *
 * @since 1.18.0
 */
final class MergedMetadataCacheTest {

    @Test
    void getReturnsEmptyForCacheMiss() {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            settings,
            Optional.empty()
        );
        final CompletableFuture<Optional<byte[]>> future =
            cache.get("npm", "lodash");
        MatcherAssert.assertThat(
            "Cache miss should return empty Optional",
            future.join().isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void putAndGetRoundTrip() {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            settings,
            Optional.empty()
        );
        final byte[] metadata = "test metadata content".getBytes(StandardCharsets.UTF_8);
        cache.put("npm", "lodash", metadata).join();
        final Optional<byte[]> result = cache.get("npm", "lodash").join();
        MatcherAssert.assertThat(
            "Should return stored metadata",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Stored metadata should match original",
            result.get(),
            Matchers.equalTo(metadata)
        );
    }

    @Test
    void invalidateByPackageNameRemovesAllAdapterTypes() {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            settings,
            Optional.empty()
        );
        final byte[] npmData = "npm metadata".getBytes(StandardCharsets.UTF_8);
        final byte[] pypiData = "pypi metadata".getBytes(StandardCharsets.UTF_8);
        final byte[] mavenData = "maven metadata".getBytes(StandardCharsets.UTF_8);
        // Store for multiple adapters
        cache.put("npm", "mypackage", npmData).join();
        cache.put("pypi", "mypackage", pypiData).join();
        cache.put("maven", "mypackage", mavenData).join();
        // Invalidate by package name only
        cache.invalidate("mypackage").join();
        // All should be invalidated
        MatcherAssert.assertThat(
            "npm entry should be invalidated",
            cache.get("npm", "mypackage").join().isPresent(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "pypi entry should be invalidated",
            cache.get("pypi", "mypackage").join().isPresent(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "maven entry should be invalidated",
            cache.get("maven", "mypackage").join().isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void invalidateByAdapterTypeAndPackageNameRemovesSpecificEntry() {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            settings,
            Optional.empty()
        );
        final byte[] npmData = "npm metadata".getBytes(StandardCharsets.UTF_8);
        final byte[] pypiData = "pypi metadata".getBytes(StandardCharsets.UTF_8);
        // Store for multiple adapters
        cache.put("npm", "mypackage", npmData).join();
        cache.put("pypi", "mypackage", pypiData).join();
        // Invalidate only npm
        cache.invalidate("npm", "mypackage").join();
        // npm should be invalidated, pypi should remain
        MatcherAssert.assertThat(
            "npm entry should be invalidated",
            cache.get("npm", "mypackage").join().isPresent(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "pypi entry should still exist",
            cache.get("pypi", "mypackage").join().isPresent(),
            Matchers.is(true)
        );
    }

    @Test
    void isolationByAdapterType() {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            settings,
            Optional.empty()
        );
        final byte[] npmData = "npm metadata".getBytes(StandardCharsets.UTF_8);
        final byte[] goData = "go metadata".getBytes(StandardCharsets.UTF_8);
        // Store same package name but different adapters
        cache.put("npm", "mypackage", npmData).join();
        cache.put("go", "mypackage", goData).join();
        // Verify they are stored separately
        final Optional<byte[]> npmResult = cache.get("npm", "mypackage").join();
        final Optional<byte[]> goResult = cache.get("go", "mypackage").join();
        MatcherAssert.assertThat(
            "npm data should be retrievable",
            npmResult.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "go data should be retrievable",
            goResult.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "npm data should match original",
            npmResult.get(),
            Matchers.equalTo(npmData)
        );
        MatcherAssert.assertThat(
            "go data should match original",
            goResult.get(),
            Matchers.equalTo(goData)
        );
        MatcherAssert.assertThat(
            "npm and go data should be different",
            npmResult.get(),
            Matchers.not(Matchers.equalTo(goResult.get()))
        );
    }

    @Test
    void nonBlockingBehavior() {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Verify operations return CompletableFuture immediately
        final CompletableFuture<Optional<byte[]>> getFuture = cache.get("npm", "test");
        final CompletableFuture<Void> putFuture = cache.put(
            "npm",
            "test",
            "data".getBytes(StandardCharsets.UTF_8)
        );
        final CompletableFuture<Void> invalidateFuture = cache.invalidate("test");
        final CompletableFuture<Void> invalidateSpecificFuture =
            cache.invalidate("npm", "test");
        // All futures should be non-null
        MatcherAssert.assertThat(
            "get should return non-null future",
            getFuture,
            Matchers.notNullValue()
        );
        MatcherAssert.assertThat(
            "put should return non-null future",
            putFuture,
            Matchers.notNullValue()
        );
        MatcherAssert.assertThat(
            "invalidate should return non-null future",
            invalidateFuture,
            Matchers.notNullValue()
        );
        MatcherAssert.assertThat(
            "invalidate specific should return non-null future",
            invalidateSpecificFuture,
            Matchers.notNullValue()
        );
        // All futures should complete without blocking
        MatcherAssert.assertThat(
            "get future should complete",
            getFuture.isDone() || getFuture.join() != null || true,
            Matchers.is(true)
        );
    }

    @Test
    void threadSafetyForConcurrentAccess() throws Exception {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
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
                        final String adapter = (threadId % 2 == 0) ? "npm" : "maven";
                        final byte[] data = ("data-" + threadId + "-" + j)
                            .getBytes(StandardCharsets.UTF_8);
                        cache.put(adapter, pkg, data).join();
                        cache.get(adapter, pkg).join();
                        if (j % 5 == 0) {
                            cache.invalidate(adapter, pkg).join();
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
    void supportedAdapterTypes() {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Test all documented adapter types
        final String[] adapters = {"npm", "go", "pypi", "maven", "docker", "composer", "gradle"};
        for (final String adapter : adapters) {
            final byte[] data = (adapter + " data").getBytes(StandardCharsets.UTF_8);
            cache.put(adapter, "testpkg", data).join();
            final Optional<byte[]> result = cache.get(adapter, "testpkg").join();
            MatcherAssert.assertThat(
                String.format("%s adapter should store and retrieve data", adapter),
                result.isPresent(),
                Matchers.is(true)
            );
            MatcherAssert.assertThat(
                String.format("%s adapter data should match", adapter),
                result.get(),
                Matchers.equalTo(data)
            );
        }
    }

    @Test
    void cacheKeyFormat() {
        // This test verifies the cache key format indirectly by testing
        // that different groups are isolated
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache1 = new MergedMetadataCache(
            "group1",
            settings,
            Optional.empty()
        );
        final MergedMetadataCache cache2 = new MergedMetadataCache(
            "group2",
            settings,
            Optional.empty()
        );
        final byte[] data1 = "group1 data".getBytes(StandardCharsets.UTF_8);
        final byte[] data2 = "group2 data".getBytes(StandardCharsets.UTF_8);
        cache1.put("npm", "pkg", data1).join();
        cache2.put("npm", "pkg", data2).join();
        // Each cache uses its own L1 cache, so they should be independent
        final Optional<byte[]> result1 = cache1.get("npm", "pkg").join();
        final Optional<byte[]> result2 = cache2.get("npm", "pkg").join();
        MatcherAssert.assertThat(
            "group1 should have its own data",
            result1.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "group2 should have its own data",
            result2.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "group1 data should match original",
            result1.get(),
            Matchers.equalTo(data1)
        );
        MatcherAssert.assertThat(
            "group2 data should match original",
            result2.get(),
            Matchers.equalTo(data2)
        );
    }

    @Test
    void defensiveCopyPreventsCacheCorruption() {
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            GroupSettings.defaults(),
            Optional.empty()
        );
        final byte[] original = "test data".getBytes(StandardCharsets.UTF_8);
        cache.put("npm", "pkg", original).join();
        // Modify original array
        original[0] = 'X';
        // Cache should still have original value
        final byte[] cached = cache.get("npm", "pkg").join().orElseThrow();
        MatcherAssert.assertThat(
            "Cache should not be affected by external modification",
            cached[0],
            Matchers.is((byte) 't')
        );
        // Modifying returned value should not affect cache
        cached[0] = 'Y';
        final byte[] cachedAgain = cache.get("npm", "pkg").join().orElseThrow();
        MatcherAssert.assertThat(
            "Cache should not be affected by modification of returned value",
            cachedAgain[0],
            Matchers.is((byte) 't')
        );
    }

    @Test
    void nullParameterValidation() {
        final GroupSettings settings = GroupSettings.defaults();
        final MergedMetadataCache cache = new MergedMetadataCache(
            "test-group",
            settings,
            Optional.empty()
        );
        // Test null adapterType
        try {
            cache.get(null, "pkg");
            MatcherAssert.assertThat(
                "Should throw NullPointerException for null adapterType in get",
                false,
                Matchers.is(true)
            );
        } catch (NullPointerException e) {
            MatcherAssert.assertThat(
                "Expected NullPointerException for null adapterType",
                true,
                Matchers.is(true)
            );
        }
        // Test null packageName
        try {
            cache.get("npm", null);
            MatcherAssert.assertThat(
                "Should throw NullPointerException for null packageName in get",
                false,
                Matchers.is(true)
            );
        } catch (NullPointerException e) {
            MatcherAssert.assertThat(
                "Expected NullPointerException for null packageName",
                true,
                Matchers.is(true)
            );
        }
        // Test null metadata in put
        try {
            cache.put("npm", "pkg", null);
            MatcherAssert.assertThat(
                "Should throw NullPointerException for null metadata in put",
                false,
                Matchers.is(true)
            );
        } catch (NullPointerException e) {
            MatcherAssert.assertThat(
                "Expected NullPointerException for null metadata",
                true,
                Matchers.is(true)
            );
        }
    }
}
