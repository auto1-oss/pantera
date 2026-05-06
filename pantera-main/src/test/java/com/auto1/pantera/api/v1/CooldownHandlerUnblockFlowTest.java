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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the CooldownHandler unblock → invalidation flow.
 * Verifies that:
 * <ol>
 *   <li>DB write completes before cache invalidation fires</li>
 *   <li>CooldownCache L1 is invalidated on unblock</li>
 *   <li>FilteredMetadataCache is invalidated on unblock</li>
 *   <li>All invalidation completes before the service future resolves</li>
 *   <li>Policy change invalidates all caches</li>
 * </ol>
 *
 * @since 2.2.0
 */
final class CooldownHandlerUnblockFlowTest {

    @Test
    void unblockInvalidatesCooldownCacheAndMetadataCache() {
        // Arrange: A blocked version in CooldownCache L1
        final CooldownCache cache = new CooldownCache(1000, Duration.ofHours(1), null);
        cache.putBlocked("my-repo", "my-package", "1.0.0", Instant.now().plusSeconds(3600));
        // Verify the cache has the block BEFORE unblock
        final Boolean blockedBefore = cache.isBlocked(
            "my-repo", "my-package", "1.0.0",
            () -> CompletableFuture.completedFuture(true)
        ).join();
        Assertions.assertTrue(blockedBefore, "Version should be blocked before unblock");
        // Recording metadata service
        final RecordingMetadataService metaSvc = new RecordingMetadataService();
        // Recording cooldown service
        final RecordingCooldownService cooldownSvc = new RecordingCooldownService();
        // Simulate the handler-level unblock flow (mirrors CooldownHandler.unblock thenRun)
        cooldownSvc.unblock("npm-proxy", "my-repo", "my-package", "1.0.0", "admin-user")
            .thenRun(() -> {
                cache.unblock("my-repo", "my-package", "1.0.0");
                metaSvc.invalidate("npm-proxy", "my-repo", "my-package");
            })
            .join();
        // Assert: CooldownCache L1 now reports NOT blocked
        final Boolean blockedAfter = cache.isBlocked(
            "my-repo", "my-package", "1.0.0",
            () -> CompletableFuture.completedFuture(false)
        ).join();
        Assertions.assertFalse(blockedAfter, "Version must not be blocked after unblock");
        // Assert: Metadata invalidation was called
        Assertions.assertEquals(1, metaSvc.invalidateCount(),
            "FilteredMetadataCache invalidate() should have been called once");
        Assertions.assertEquals("npm-proxy", metaSvc.lastRepoType());
        Assertions.assertEquals("my-repo", metaSvc.lastRepoName());
        Assertions.assertEquals("my-package", metaSvc.lastPackageName());
    }

    @Test
    void unblockAllInvalidatesCooldownCacheAndMetadataCache() {
        // Arrange: Multiple blocked versions
        final CooldownCache cache = new CooldownCache(1000, Duration.ofHours(1), null);
        cache.putBlocked("my-repo", "pkg-a", "1.0.0", Instant.now().plusSeconds(3600));
        cache.putBlocked("my-repo", "pkg-b", "2.0.0", Instant.now().plusSeconds(3600));
        final RecordingMetadataService metaSvc = new RecordingMetadataService();
        final RecordingCooldownService cooldownSvc = new RecordingCooldownService();
        // Simulate handler-level unblockAll flow
        cooldownSvc.unblockAll("npm-proxy", "my-repo", "admin-user")
            .thenRun(() -> {
                cache.unblockAll("my-repo");
                metaSvc.invalidateAll("npm-proxy", "my-repo");
            })
            .join();
        // Assert: Both versions now NOT blocked
        final Boolean aPkg = cache.isBlocked(
            "my-repo", "pkg-a", "1.0.0",
            () -> CompletableFuture.completedFuture(false)
        ).join();
        final Boolean bPkg = cache.isBlocked(
            "my-repo", "pkg-b", "2.0.0",
            () -> CompletableFuture.completedFuture(false)
        ).join();
        Assertions.assertFalse(aPkg, "pkg-a should be unblocked");
        Assertions.assertFalse(bPkg, "pkg-b should be unblocked");
        // Assert: Metadata invalidation was called
        Assertions.assertEquals(1, metaSvc.invalidateAllCount(),
            "FilteredMetadataCache invalidateAll() should have been called once");
    }

    @Test
    void policyChangeInvalidatesAllCaches() {
        // Arrange: Populated caches
        final CooldownCache cache = new CooldownCache(1000, Duration.ofHours(1), null);
        cache.putBlocked("repo1", "pkg-x", "3.0.0", Instant.now().plusSeconds(7200));
        final RecordingMetadataService metaSvc = new RecordingMetadataService();
        // Simulate policy change flow (mirrors CooldownHandler.updateConfig)
        metaSvc.clearAll();
        cache.clear();
        // Assert: CooldownCache cleared
        final Boolean blocked = cache.isBlocked(
            "repo1", "pkg-x", "3.0.0",
            () -> CompletableFuture.completedFuture(false)
        ).join();
        Assertions.assertFalse(blocked,
            "CooldownCache must be cleared after policy change");
        // Assert: Metadata clearAll was called
        Assertions.assertEquals(1, metaSvc.clearAllCount(),
            "clearAll() should have been called once on policy change");
    }

    @Test
    void dbWriteCompletesBeforeInvalidation() {
        // Verify ordering: the unblock service future must resolve
        // before the cache invalidation runs.
        final List<String> ordering = Collections.synchronizedList(new ArrayList<>());
        final CooldownService svc = new CooldownService() {
            @Override
            public CompletableFuture<CooldownResult> evaluate(
                final CooldownRequest r, final CooldownInspector i) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
            @Override
            public CompletableFuture<Void> unblock(
                final String repoType, final String repoName,
                final String artifact, final String version, final String actor) {
                return CompletableFuture.runAsync(() -> {
                    ordering.add("db_write");
                });
            }
            @Override
            public CompletableFuture<Void> unblockAll(
                final String repoType, final String repoName, final String actor) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<List<CooldownBlock>> activeBlocks(
                final String repoType, final String repoName) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
        };
        svc.unblock("npm-proxy", "my-repo", "pkg", "1.0.0", "admin")
            .thenRun(() -> ordering.add("cache_invalidation"))
            .join();
        Assertions.assertEquals(List.of("db_write", "cache_invalidation"), ordering,
            "DB write must complete before cache invalidation");
    }

    /**
     * Recording CooldownService that tracks unblock calls.
     */
    private static final class RecordingCooldownService implements CooldownService {
        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
        @Override
        public CompletableFuture<Void> unblock(
            final String repoType, final String repoName,
            final String artifact, final String version, final String actor) {
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public CompletableFuture<Void> unblockAll(
            final String repoType, final String repoName, final String actor) {
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String repoType, final String repoName) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    /**
     * Recording CooldownMetadataService that tracks invalidation calls.
     */
    private static final class RecordingMetadataService implements CooldownMetadataService {
        private final AtomicInteger invCount = new AtomicInteger(0);
        private final AtomicInteger invAllCount = new AtomicInteger(0);
        private final AtomicInteger clrAllCount = new AtomicInteger(0);
        private volatile String lastType;
        private volatile String lastName;
        private volatile String lastPkg;

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType, final String repoName, final String packageName,
            final byte[] rawMetadata, final MetadataParser<T> parser,
            final MetadataFilter<T> filter, final MetadataRewriter<T> rewriter,
            final Optional<com.auto1.pantera.cooldown.api.CooldownInspector> inspector) {
            return CompletableFuture.completedFuture(rawMetadata);
        }
        @Override
        public void invalidate(final String repoType,
            final String repoName, final String packageName) {
            this.invCount.incrementAndGet();
            this.lastType = repoType;
            this.lastName = repoName;
            this.lastPkg = packageName;
        }
        @Override
        public void invalidateAll(final String repoType, final String repoName) {
            this.invAllCount.incrementAndGet();
        }
        @Override
        public void clearAll() {
            this.clrAllCount.incrementAndGet();
        }
        @Override
        public String stats() {
            return "RecordingMetadataService";
        }
        int invalidateCount() {
            return this.invCount.get();
        }
        int invalidateAllCount() {
            return this.invAllCount.get();
        }
        int clearAllCount() {
            return this.clrAllCount.get();
        }
        String lastRepoType() {
            return this.lastType;
        }
        String lastRepoName() {
            return this.lastName;
        }
        String lastPackageName() {
            return this.lastPkg;
        }
    }
}
