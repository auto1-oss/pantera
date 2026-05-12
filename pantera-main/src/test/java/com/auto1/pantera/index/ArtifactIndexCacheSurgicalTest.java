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
package com.auto1.pantera.index;

import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Pins the surgical-invalidation contract for {@link ArtifactIndexCache}.
 *
 * <p>Before Track 2 the only invalidation primitive was {@code invalidate(name)},
 * which dropped both the positive and negative tier for an artifact name on
 * every upload — forcing a DB round-trip on the very next read even though
 * the only change was "now one more repo serves this artifact". The current
 * API expresses uploads and deletes as deltas:
 *
 * <ul>
 *   <li>{@code recordUpload(name, repo)} — append repo to the positive entry
 *       (creating it if absent), drop the negative entry. No DB hit on the
 *       subsequent read.</li>
 *   <li>{@code recordDelete(name, repo)} — remove repo from the positive
 *       entry; drop the entry when the list becomes empty.</li>
 * </ul>
 *
 * <p>This suite exercises L1-only operation (the path taken by every
 * single-instance deployment and every test that doesn't wire Valkey).
 *
 * @since 2.2.0
 */
final class ArtifactIndexCacheSurgicalTest {

    @Test
    @DisplayName("recordUpload appends to existing positive list without re-hitting DB")
    void recordUploadAppendsWithoutDbHit() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final ArtifactIndex delegate = stubLocate(name -> {
            calls.incrementAndGet();
            return Optional.of(List.of("maven_proxy"));
        });
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        // Warm the cache with the proxy entry.
        cache.locateByName("com.google.guava:guava").get();
        assertThat("warming costs one DB call", calls.get(), new IsEqual<>(1));
        // Now a local upload lands.
        cache.recordUpload("com.google.guava:guava", "maven_local");
        final Optional<List<String>> updated = cache.locateByName("com.google.guava:guava").get();
        assertThat(
            "positive entry now lists both repos",
            updated.orElseThrow(),
            new IsEqual<>(List.of("maven_proxy", "maven_local"))
        );
        assertThat("no extra DB call after recordUpload", calls.get(), new IsEqual<>(1));
    }

    @Test
    @DisplayName("recordUpload on absent entry seeds positive list directly")
    void recordUploadSeedsAbsentEntry() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final ArtifactIndex delegate = stubLocate(name -> {
            calls.incrementAndGet();
            return Optional.of(Collections.<String>emptyList());
        });
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        cache.recordUpload("freshly:published", "maven_local");
        final Optional<List<String>> seeded = cache.locateByName("freshly:published").get();
        assertThat(
            "positive entry was created from the delta",
            seeded.orElseThrow(),
            new IsEqual<>(List.of("maven_local"))
        );
        assertThat("no DB call needed", calls.get(), new IsEqual<>(0));
    }

    @Test
    @DisplayName("recordUpload is idempotent for the same (name, repo) pair")
    void recordUploadIdempotent() throws Exception {
        final ArtifactIndex delegate = stubLocate(name ->
            Optional.of(List.of("maven_proxy"))
        );
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        cache.locateByName("dup:test").get();
        cache.recordUpload("dup:test", "maven_proxy");
        cache.recordUpload("dup:test", "maven_proxy");
        cache.recordUpload("dup:test", "maven_proxy");
        final Optional<List<String>> result = cache.locateByName("dup:test").get();
        assertThat(
            "list is unchanged — no duplicates",
            result.orElseThrow(),
            new IsEqual<>(List.of("maven_proxy"))
        );
    }

    @Test
    @DisplayName("recordUpload drops a stale negative entry")
    void recordUploadClearsNegativeEntry() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        // First DB lookup returns empty (negative cached). After recordUpload
        // a subsequent lookup must NOT see the negative entry — we seeded
        // positive in-place.
        final ArtifactIndex delegate = stubLocate(name -> {
            calls.incrementAndGet();
            return Optional.of(Collections.<String>emptyList());
        });
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        final Optional<List<String>> firstMiss = cache.locateByName("publish:me").get();
        assertThat("first read is negative", firstMiss.orElseThrow().isEmpty(), new IsEqual<>(true));
        cache.recordUpload("publish:me", "maven_local");
        final Optional<List<String>> postUpload = cache.locateByName("publish:me").get();
        assertThat(
            "post-upload read sees the freshly recorded repo",
            postUpload.orElseThrow(),
            new IsEqual<>(List.of("maven_local"))
        );
        assertThat(
            "negative cache was cleared without a second DB call",
            calls.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    @DisplayName("recordDelete shrinks positive list and drops empty entry")
    void recordDeleteShrinksThenDrops() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final ArtifactIndex delegate = stubLocate(name -> {
            calls.incrementAndGet();
            return Optional.of(List.of("maven_local", "maven_proxy"));
        });
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        cache.locateByName("two:holders").get();
        cache.recordDelete("two:holders", "maven_proxy");
        final Optional<List<String>> after = cache.locateByName("two:holders").get();
        assertThat(
            "one repo removed, the other remains",
            after.orElseThrow(),
            new IsEqual<>(List.of("maven_local"))
        );
        assertThat("no extra DB call", calls.get(), new IsEqual<>(1));
        // Removing the last holder drops the cached entry entirely so the
        // next read goes back to the DB (which now also reflects the
        // delete).
        final ArtifactIndex emptyDelegate = stubLocate(name -> {
            calls.incrementAndGet();
            return Optional.of(Collections.<String>emptyList());
        });
        final ArtifactIndexCache cache2 = new ArtifactIndexCache(emptyDelegate);
        cache2.recordUpload("only:one", "maven_local");
        cache2.recordDelete("only:one", "maven_local");
        cache2.locateByName("only:one").get();
        assertThat(
            "post-delete-empty read does go to the DB",
            calls.get(),
            new IsEqual<>(2)
        );
    }

    @Test
    @DisplayName("invalidate still drops both tiers (escape-hatch contract preserved)")
    void invalidateClearsBothTiers() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final ArtifactIndex delegate = stubLocate(name -> {
            calls.incrementAndGet();
            return Optional.of(List.of("maven_proxy"));
        });
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        cache.locateByName("escape:hatch").get();
        cache.invalidate("escape:hatch");
        cache.locateByName("escape:hatch").get();
        assertThat(
            "invalidate forces a fresh DB lookup",
            calls.get(),
            new IsEqual<>(2)
        );
    }

    // ---- helpers ----

    private static ArtifactIndex stubLocate(
        final Function<String, Optional<List<String>>> fn
    ) {
        return new StubIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                return CompletableFuture.completedFuture(fn.apply(name));
            }
        };
    }

    /** Minimal {@link ArtifactIndex}; every unused method throws. */
    private static class StubIndex implements ArtifactIndex {
        @Override
        public CompletableFuture<Void> index(final ArtifactDocument doc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> remove(final String repoName, final String artifactPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<SearchResult> search(
            final String query, final int maxResults, final int offset
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<String>> locate(final String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() throws IOException {
            // no-op
        }
    }
}
