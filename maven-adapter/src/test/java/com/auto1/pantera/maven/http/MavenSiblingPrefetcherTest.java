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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.ProxyCacheWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MavenSiblingPrefetcher} — Track 4 companion-artifact
 * prefetch.
 *
 * @since 2.2.0
 */
final class MavenSiblingPrefetcherTest {

    @Test
    @DisplayName(".jar primary derives the matching .pom sibling")
    void deriveJarToPom() {
        final List<Key> derived = MavenSiblingPrefetcher.derive(
            new Key.From("com/example/foo/1.0/foo-1.0.jar")
        );
        assertThat("one sibling derived", derived, hasSize(1));
        assertThat(
            "sibling is the .pom partner",
            derived.get(0).string(),
            equalTo("com/example/foo/1.0/foo-1.0.pom")
        );
    }

    @Test
    @DisplayName(".pom primary derives the matching .jar sibling")
    void derivePomToJar() {
        final List<Key> derived = MavenSiblingPrefetcher.derive(
            new Key.From("com/example/foo/1.0/foo-1.0.pom")
        );
        assertThat(derived, hasSize(1));
        assertThat(
            derived.get(0).string(),
            equalTo("com/example/foo/1.0/foo-1.0.jar")
        );
    }

    @Test
    @DisplayName(".sha1 (and other non-primary extensions) yield no siblings")
    void noSiblingsForChecksums() {
        assertThat(
            MavenSiblingPrefetcher.derive(
                new Key.From("com/example/foo/1.0/foo-1.0.jar.sha1")
            ),
            empty()
        );
        // -sources / -javadoc / .module are intentionally NOT in the partner
        // table — they would inflate upstream amplification without payoff
        // for the common dependency-resolve walk.
        assertThat(
            MavenSiblingPrefetcher.derive(
                new Key.From("com/example/foo/1.0/foo-1.0-sources.jar")
            ),
            hasSize(1)
        );
    }

    @Test
    @DisplayName("onPrimaryCached fetches the sibling and lands it in cache")
    void onPrimaryCachedFetchesSibling() throws Exception {
        final InMemoryStorage cache = new InMemoryStorage();
        final byte[] pomBody = "<project>foo</project>".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger upstreamHits = new AtomicInteger();
        final Slice upstream = countingUpstream(pomBody, upstreamHits);
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            final MavenSiblingPrefetcher prefetcher = new MavenSiblingPrefetcher(
                upstream, cache, writer, "maven-proxy",
                "https://upstream.example", exec
            );
            // Pretend foo-1.0.jar was just cached by the foreground path.
            // Prefetcher should now fetch foo-1.0.pom from upstream.
            prefetcher.onPrimaryCached(
                new Key.From("com/example/foo/1.0/foo-1.0.jar")
            );
            exec.shutdown();
            assertTrue(
                exec.awaitTermination(5L, TimeUnit.SECONDS),
                "prefetcher drained in time"
            );
            assertTrue(
                cache.exists(new Key.From("com/example/foo/1.0/foo-1.0.pom")).join(),
                "sibling .pom landed in cache"
            );
            assertTrue(
                cache.exists(new Key.From("com/example/foo/1.0/foo-1.0.pom.sha1")).join(),
                "sibling .sha1 landed in cache"
            );
            assertThat(
                "exactly one fetch each for .pom + .sha1 = 2 upstream calls",
                upstreamHits.get(),
                equalTo(2)
            );
        } finally {
            if (!exec.isTerminated()) {
                exec.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("sibling already cached → no upstream call")
    void skipsWhenAlreadyCached() throws Exception {
        final InMemoryStorage cache = new InMemoryStorage();
        // Pre-seed the sibling so the prefetcher's exists() check finds it.
        cache.save(
            new Key.From("com/example/foo/1.0/foo-1.0.pom"),
            new Content.From("already".getBytes(StandardCharsets.UTF_8))
        ).join();
        final AtomicInteger upstreamHits = new AtomicInteger();
        final Slice upstream = countingUpstream(
            "should-not-be-called".getBytes(StandardCharsets.UTF_8), upstreamHits
        );
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            final MavenSiblingPrefetcher prefetcher = new MavenSiblingPrefetcher(
                upstream, cache, writer, "maven-proxy",
                "https://upstream.example", exec
            );
            prefetcher.onPrimaryCached(
                new Key.From("com/example/foo/1.0/foo-1.0.jar")
            );
            exec.shutdown();
            assertTrue(exec.awaitTermination(5L, TimeUnit.SECONDS));
            assertThat(
                "no upstream calls when sibling already cached",
                upstreamHits.get(),
                equalTo(0)
            );
        } finally {
            if (!exec.isTerminated()) {
                exec.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("upstream 404 → sibling stays absent, no crash")
    void siblingUpstream404IsSilent() throws Exception {
        final InMemoryStorage cache = new InMemoryStorage();
        final AtomicInteger upstreamHits = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamHits.incrementAndGet();
            return ResponseBuilder.notFound().completedFuture();
        };
        final ProxyCacheWriter writer = new ProxyCacheWriter(cache, "maven-proxy");
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            final MavenSiblingPrefetcher prefetcher = new MavenSiblingPrefetcher(
                upstream, cache, writer, "maven-proxy",
                "https://upstream.example", exec
            );
            prefetcher.onPrimaryCached(
                new Key.From("com/example/foo/1.0/foo-1.0.jar")
            );
            exec.shutdown();
            assertTrue(exec.awaitTermination(5L, TimeUnit.SECONDS));
            assertFalse(
                cache.exists(new Key.From("com/example/foo/1.0/foo-1.0.pom")).join(),
                "sibling .pom NOT cached on upstream 404"
            );
        } finally {
            if (!exec.isTerminated()) {
                exec.shutdownNow();
            }
        }
    }

    /**
     * Build an upstream test slice that returns {@code body} for the
     * primary and the matching SHA-1 hex for the {@code .sha1} sidecar.
     * Increments {@code hits} on every request — used to assert dedup +
     * skip-when-cached invariants.
     */
    private static Slice countingUpstream(final byte[] body, final AtomicInteger hits) {
        final String sha1 = sha1Hex(body);
        return (line, headers, ignored) -> {
            hits.incrementAndGet();
            if (line.uri().getPath().endsWith(".sha1")) {
                return ResponseBuilder.ok()
                    .body(sha1.getBytes(StandardCharsets.UTF_8))
                    .completedFuture();
            }
            return ResponseBuilder.ok().body(body).completedFuture();
        };
    }

    private static String sha1Hex(final byte[] body) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(body)
            );
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
