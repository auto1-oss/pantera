/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 */
package com.auto1.pantera.index;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ArtifactIndexCache}.
 *
 * @since 2.2.0
 */
final class ArtifactIndexCacheTest {

    @Test
    void positiveHitShortCircuitsDelegate() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final ArtifactIndex delegate = stubLocate(name -> {
            calls.incrementAndGet();
            return Optional.of(List.of("maven_proxy"));
        });
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        cache.locateByName("com.google.guava:guava").get();
        cache.locateByName("com.google.guava:guava").get();
        cache.locateByName("com.google.guava:guava").get();
        assertThat(calls.get(), equalTo(1));
    }

    @Test
    void negativeHitShortCircuitsDelegateAfterFirstMiss() throws Exception {
        // Optional.of(emptyList) means "DB succeeded, no row" -> negative cache.
        // Optional.empty() means "DB error" -> must NOT short-circuit. This test
        // pins the FORMER (the cacheable case).
        final AtomicInteger calls = new AtomicInteger();
        final ArtifactIndex delegate = stubLocate(name -> {
            calls.incrementAndGet();
            return Optional.of(Collections.emptyList());
        });
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        final Optional<List<String>> first = cache.locateByName("never:exists").get();
        final Optional<List<String>> second = cache.locateByName("never:exists").get();
        assertThat(first.isPresent(), equalTo(true));
        assertThat(first.get().isEmpty(), equalTo(true));
        assertThat(second.isPresent(), equalTo(true));
        assertThat(second.get().isEmpty(), equalTo(true));
        assertThat(calls.get(), equalTo(1));
    }

    @Test
    void concurrentLookupsForSameNameCoalesce() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final CompletableFuture<Optional<List<String>>> gate = new CompletableFuture<>();
        final ArtifactIndex delegate = new TestStubIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                calls.incrementAndGet();
                return gate;
            }
        };
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        final CompletableFuture<Optional<List<String>>> a = cache.locateByName("popular:artifact");
        final CompletableFuture<Optional<List<String>>> b = cache.locateByName("popular:artifact");
        final CompletableFuture<Optional<List<String>>> c = cache.locateByName("popular:artifact");
        // All three should be sharing one in-flight future; delegate not yet called twice.
        assertThat(calls.get(), equalTo(1));
        gate.complete(Optional.of(List.of("maven_proxy")));
        assertThat(a.get().get(), equalTo(List.of("maven_proxy")));
        assertThat(b.get().get(), equalTo(List.of("maven_proxy")));
        assertThat(c.get().get(), equalTo(List.of("maven_proxy")));
        assertThat(calls.get(), equalTo(1));
    }

    @Test
    void invalidateDropsBothPositiveAndNegativeEntries() throws Exception {
        // First call: DB has no row -> negative cache populated.
        // After invalidate: DB now has the row -> must hit DB again, not return cached negative.
        final AtomicInteger calls = new AtomicInteger();
        final ArtifactIndex delegate = stubLocate(name -> {
            final int n = calls.incrementAndGet();
            if (n == 1) {
                return Optional.of(Collections.<String>emptyList());
            }
            return Optional.of(List.of("maven_local"));
        });
        final ArtifactIndexCache cache = new ArtifactIndexCache(delegate);
        final Optional<List<String>> beforePublish = cache.locateByName("freshly:published").get();
        assertThat(beforePublish.get().isEmpty(), is(true));
        cache.invalidate("freshly:published");
        final Optional<List<String>> afterPublish = cache.locateByName("freshly:published").get();
        assertThat(afterPublish.get(), equalTo(List.of("maven_local")));
        assertThat(calls.get(), equalTo(2));
    }

    // ---- helpers ----

    /**
     * Build a minimal ArtifactIndex whose only meaningful method is
     * locateByName, driven by the supplied function. Every other interface
     * method throws UnsupportedOperationException so tests fail loud if
     * something unexpected is called.
     */
    private static ArtifactIndex stubLocate(
        final Function<String, Optional<List<String>>> fn
    ) {
        return new TestStubIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                return CompletableFuture.completedFuture(fn.apply(name));
            }
        };
    }

    /**
     * Bare-minimum ArtifactIndex for tests: every abstract method throws unless
     * overridden by a subclass / anonymous class. Default-method behaviour is
     * inherited from the interface (most delegate to the abstract methods and
     * will therefore throw if accidentally exercised).
     */
    private static class TestStubIndex implements ArtifactIndex {
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
