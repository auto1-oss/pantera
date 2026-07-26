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
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WS5.5 — proves the {@code /simple/} filtered-index cache: a second identical
 * request is served from the shared {@link FilteredMetadataCache} without
 * re-evaluating cooldown, and the cache self-busts on both explicit
 * invalidation (unblock / upload / proxy refresh hooks) and upstream content
 * change. Invocation-count based per the project testing doctrine — no
 * wall-clock assertions.
 *
 * @since 2.3.0
 */
final class PypiSimpleHandlerCacheTest {

    private static final RequestLine LINE =
        new RequestLine(RqMethod.GET, "/simple/foo/");

    private ScriptedSlice upstream;
    private CountingCooldown cooldown;
    private FilteredMetadataCache cache;
    private PypiSimpleHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new CountingCooldown();
        // Single-tier L1 cache (no Valkey) — deterministic, no network.
        this.cache = new FilteredMetadataCache(
            1_000, Duration.ofHours(24), Duration.ofHours(24), null
        );
        this.handler = new PypiSimpleHandler(
            this.upstream, this.cooldown, "pypi-proxy", "pypi-test", this.cache
        );
    }

    @Test
    void secondIdenticalRequestServedFromCacheWithoutReEvaluating() throws Exception {
        this.upstream.put("/simple/foo/", jsonIndex("foo", "1.0.0", "1.1.0", "1.2.0"));
        this.cooldown.block("1.2.0");

        final Response first = this.handler.handle(
            LINE, true, "alice", Headers.EMPTY
        ).get();
        final int afterFirst = this.cooldown.count();

        final Response second = this.handler.handle(
            LINE, true, "alice", Headers.EMPTY
        ).get();

        MatcherAssert.assertThat(
            "first request must evaluate cooldown once per distinct version",
            afterFirst, new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "a cache hit must NOT re-evaluate cooldown for any version",
            this.cooldown.count(), new IsEqual<>(afterFirst)
        );
        MatcherAssert.assertThat(
            "the blocked version stays filtered out of the cached serve",
            body(second), new IsNot<>(new StringContains("1.2.0"))
        );
        MatcherAssert.assertThat(
            "surviving versions are still served from cache",
            body(second), new StringContains("1.1.0")
        );
    }

    @Test
    void invalidationDropsCacheAndForcesReEvaluation() throws Exception {
        this.upstream.put("/simple/foo/", jsonIndex("foo", "1.0.0", "1.1.0", "1.2.0"));
        this.cooldown.block("1.2.0");

        this.handler.handle(LINE, true, "alice", Headers.EMPTY).get();
        final int afterFirst = this.cooldown.count();

        // Simulate an unblock / upload / proxy-refresh hook dropping the entry.
        this.cache.invalidate("pypi-proxy", "pypi-test", "foo");

        this.handler.handle(LINE, true, "alice", Headers.EMPTY).get();

        MatcherAssert.assertThat(
            "after invalidation the next request must re-evaluate every version",
            this.cooldown.count(), new IsEqual<>(afterFirst * 2)
        );
    }

    @Test
    void upstreamContentChangeBustsCacheAndRevealsNewVersion() throws Exception {
        this.upstream.put("/simple/foo/", jsonIndex("foo", "1.0.0", "1.1.0", "1.2.0"));
        this.cooldown.block("1.2.0");

        final Response before = this.handler.handle(
            LINE, true, "alice", Headers.EMPTY
        ).get();
        final int afterFirst = this.cooldown.count();
        MatcherAssert.assertThat(
            "new version is not present before it is published upstream",
            body(before), new IsNot<>(new StringContains("1.3.0"))
        );

        // Upstream publishes a new version — bytes (and their fingerprint) change.
        this.upstream.put("/simple/foo/", jsonIndex("foo", "1.0.0", "1.1.0", "1.2.0", "1.3.0"));

        final Response after = this.handler.handle(
            LINE, true, "alice", Headers.EMPTY
        ).get();

        MatcherAssert.assertThat(
            "changed upstream content must re-evaluate cooldown (4 versions now)",
            this.cooldown.count(), new IsEqual<>(afterFirst + 4)
        );
        MatcherAssert.assertThat(
            "the newly-published version becomes visible on the next request",
            body(after), new StringContains("1.3.0")
        );
    }

    @Test
    void identicalContentDoesNotBustCache() throws Exception {
        this.upstream.put("/simple/foo/", jsonIndex("foo", "1.0.0", "1.1.0"));

        this.handler.handle(LINE, true, "alice", Headers.EMPTY).get();
        final int afterFirst = this.cooldown.count();
        // Same bytes on the next request — fingerprint unchanged, cache retained.
        this.handler.handle(LINE, true, "alice", Headers.EMPTY).get();

        MatcherAssert.assertThat(
            "identical upstream content must be served from cache, not re-filtered",
            this.cooldown.count(), new IsEqual<>(afterFirst)
        );
    }

    // ===== Helpers =====

    private static String body(final Response resp) throws Exception {
        return new String(resp.body().asBytesFuture().get(), StandardCharsets.UTF_8);
    }

    /**
     * Build a minimal PEP 691 JSON Simple Index for {@code pkg} with one
     * {@code .tar.gz} file per version, each carrying an {@code upload-time}.
     */
    private static String jsonIndex(final String pkg, final String... versions) {
        final StringBuilder json = new StringBuilder();
        json.append("{\"meta\":{\"api-version\":\"1.1\"},\"name\":\"")
            .append(pkg).append("\",\"files\":[");
        for (int idx = 0; idx < versions.length; idx++) {
            if (idx > 0) {
                json.append(',');
            }
            final String ver = versions[idx];
            json.append("{\"filename\":\"").append(pkg).append('-').append(ver)
                .append(".tar.gz\",\"url\":\"../../packages/").append(pkg).append('-')
                .append(ver).append(".tar.gz\",\"hashes\":{\"sha256\":\"abc")
                .append(idx).append("\"},\"upload-time\":\"2020-01-01T00:00:0")
                .append(idx).append("Z\"}");
        }
        json.append("]}");
        return json.toString();
    }

    /** Minimal scripted {@link Slice} whose script can change between calls. */
    private static final class ScriptedSlice implements Slice {
        private final Map<String, byte[]> script = new ConcurrentHashMap<>();

        void put(final String path, final String content) {
            this.script.put(path, content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final byte[] content = this.script.get(line.uri().getPath());
            if (content == null) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(content).build()
            );
        }
    }

    /** Cooldown service that counts every per-version evaluation. */
    private static final class CountingCooldown implements CooldownService {
        private final Set<String> blocked = ConcurrentHashMap.newKeySet();
        private final AtomicInteger evaluations = new AtomicInteger();

        void block(final String... versions) {
            for (final String version : versions) {
                this.blocked.add(version);
            }
        }

        int count() {
            return this.evaluations.get();
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return this.decide(request);
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request, final Optional<Instant> knownReleaseDate
        ) {
            return this.decide(request);
        }

        private CompletableFuture<CooldownResult> decide(final CooldownRequest request) {
            this.evaluations.incrementAndGet();
            if (!this.blocked.contains(request.version())) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
            final CooldownBlock block = new CooldownBlock(
                request.repoType(),
                request.repoName(),
                request.artifact(),
                request.version(),
                CooldownReason.FRESH_RELEASE,
                Instant.now(),
                Instant.now().plusSeconds(3_600),
                List.of()
            );
            return CompletableFuture.completedFuture(CooldownResult.blocked(block));
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String repoType, final String repoName,
            final String artifact, final String version, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String repoType, final String repoName, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String repoType, final String repoName
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
