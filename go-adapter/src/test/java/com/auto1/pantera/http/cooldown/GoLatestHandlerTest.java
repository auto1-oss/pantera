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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link GoLatestHandler} — the orchestration layer that
 * rewrites {@code /@latest} responses when the upstream version is in
 * cooldown.
 *
 * <p>Uses a scripted {@link Slice} that returns canned {@code /@latest}
 * and {@code /@v/list} bodies per path, and a scripted
 * {@link CooldownService} whose block set is mutable from each test. No
 * {@code mockito} used — the scripts are small enough to express inline.</p>
 *
 * @since 2.2.0
 */
final class GoLatestHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedSlice upstream;
    private ScriptedCooldown cooldown;
    private CooldownInspector inspector;
    private GoLatestHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new ScriptedCooldown();
        this.inspector = new NullInspector();
        this.handler = new GoLatestHandler(
            this.upstream, this.cooldown, this.inspector, "go-proxy", "go-test"
        );
    }

    @Test
    void matchesLatestPath() {
        assertThat(
            this.handler.matches("/github.com/foo/bar/@latest"),
            is(true)
        );
        assertThat(
            this.handler.matches("/github.com/foo/bar/@v/list"),
            is(false)
        );
    }

    @Test
    void passesThroughWhenLatestNotBlocked() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            latestJson("v1.2.3", "2024-05-12T00:00:00Z")
        );
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("Version").asText(), equalTo("v1.2.3"));
        assertThat(node.get("Time").asText(), equalTo("2024-05-12T00:00:00Z"));
    }

    @Test
    void rewritesToFallbackWhenLatestBlocked() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            latestJson("v1.2.3", "2024-05-12T00:00:00Z")
        );
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.2.0\nv1.2.1\nv1.2.2\nv1.2.3\n"
        );
        this.cooldown.block("v1.2.3");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("Version").asText(), equalTo("v1.2.2"));
        // Time cleared in the rewritten payload.
        assertThat(node.get("Time"), is(nullValue()));
    }

    @Test
    void preservesOriginInRewrittenPayload() throws Exception {
        final String upstreamJson =
            "{\"Version\":\"v1.2.3\",\"Time\":\"2024-05-12T00:00:00Z\","
                + "\"Origin\":{\"VCS\":\"git\",\"URL\":\"https://x/y\"}}";
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            upstreamJson
        );
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.2.0\nv1.2.3\n"
        );
        this.cooldown.block("v1.2.3");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("Version").asText(), equalTo("v1.2.0"));
        assertThat(node.get("Origin"), is(notNullValue()));
        assertThat(node.get("Origin").get("VCS").asText(), equalTo("git"));
    }

    @Test
    void returnsForbiddenWhenAllVersionsBlocked() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            latestJson("v1.2.3", null)
        );
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.2.0\nv1.2.1\nv1.2.2\nv1.2.3\n"
        );
        this.cooldown.block("v1.2.0", "v1.2.1", "v1.2.2", "v1.2.3");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(403));
        final String body = new String(bodyToBytes(resp), StandardCharsets.UTF_8);
        assertThat(body, containsString("cooldown"));
    }

    @Test
    void passesMalformedUpstreamJsonThrough() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            "not-a-json-at-all"
        );
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final String body = new String(bodyToBytes(resp), StandardCharsets.UTF_8);
        assertThat(body, equalTo("not-a-json-at-all"));
    }

    @Test
    void handlesPseudoVersionsWhenPickingFallback() throws Exception {
        // Mix of tagged and pseudo-versions; v1.2.0 is newest tagged,
        // pseudo-version is older per semver (v0.0.0 major).
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            latestJson("v1.2.0", null)
        );
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v0.0.0-20240101000000-abc123def456\nv1.1.0\nv1.2.0\n"
        );
        this.cooldown.block("v1.2.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        // Highest non-blocked per semver = v1.1.0 (pseudo-version is v0.0.0).
        assertThat(node.get("Version").asText(), equalTo("v1.1.0"));
    }

    @Test
    void returnsForbiddenWhenListEndpointIsMissing() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            latestJson("v1.2.3", null)
        );
        // No /@v/list scripted -> upstream returns 404.
        this.cooldown.block("v1.2.3");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(403));
    }

    @Test
    void forwardsNonSuccessUpstreamStatus() throws Exception {
        // Upstream 404 for /@latest -> the handler forwards the 404 without
        // rewriting.
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/missing/@latest"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void fetchesListOnlyWhenLatestBlocked() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            latestJson("v1.2.3", null)
        );
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.2.0\nv1.2.1\nv1.2.2\nv1.2.3\n"
        );
        // v1.2.3 not blocked — list fetch must not happen.
        this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        assertThat(
            this.upstream.hits("/github.com/foo/bar/@v/list"),
            equalTo(0)
        );
        assertThat(
            this.upstream.hits("/github.com/foo/bar/@latest"),
            equalTo(1)
        );
    }

    // ===== Helpers =====

    private static String latestJson(final String version, final String time) {
        if (time == null) {
            return "{\"Version\":\"" + version + "\"}";
        }
        return "{\"Version\":\"" + version + "\",\"Time\":\"" + time + "\"}";
    }

    private static byte[] bodyToBytes(final Response resp) throws Exception {
        return resp.body().asBytesFuture().get();
    }

    /** Minimal scripted {@link Slice}: serves canned bodies for exact paths. */
    private static final class ScriptedSlice implements Slice {
        private final Map<String, byte[]> script = new HashMap<>();
        private final Map<String, AtomicInteger> hits = new HashMap<>();

        void put(final String path, final String body) {
            this.script.put(path, body.getBytes(StandardCharsets.UTF_8));
            this.hits.put(path, new AtomicInteger(0));
        }

        int hits(final String path) {
            final AtomicInteger counter = this.hits.get(path);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            final byte[] content = this.script.get(path);
            final AtomicInteger counter = this.hits.computeIfAbsent(
                path, k -> new AtomicInteger(0)
            );
            counter.incrementAndGet();
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

    /** Scripted {@link CooldownService}: flags listed versions as blocked. */
    private static final class ScriptedCooldown implements CooldownService {
        private final Set<String> blocked = new HashSet<>();

        void block(final String... versions) {
            for (final String v : versions) {
                this.blocked.add(v);
            }
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
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

    /** Inspector that returns nothing — release dates fetched on-demand. */
    private static final class NullInspector implements CooldownInspector {

        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
