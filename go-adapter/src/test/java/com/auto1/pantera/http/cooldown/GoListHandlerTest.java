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
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link GoListHandler} — the orchestration layer that
 * filters cooldown-blocked versions out of {@code /@v/list} responses.
 *
 * <p>Structured exactly like {@link GoLatestHandlerTest}: an in-memory
 * scripted {@link Slice} returns canned bodies for exact paths, and a
 * scripted {@link CooldownService} marks a mutable set of versions as
 * blocked. This keeps the handler contract tested without a Vertx or
 * database dependency.</p>
 *
 * @since 2.2.0
 */
final class GoListHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedSlice upstream;
    private ScriptedCooldown cooldown;
    private CooldownInspector inspector;
    private GoListHandler handler;
    private GoLatestHandler latestHandler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new ScriptedCooldown();
        this.inspector = new NullInspector();
        this.handler = new GoListHandler(
            this.upstream, this.cooldown, this.inspector, "go-proxy", "go-test"
        );
        this.latestHandler = new GoLatestHandler(
            this.upstream, this.cooldown, this.inspector, "go-proxy", "go-test"
        );
    }

    @Test
    void matchesListPathButNotLatest() {
        assertThat(
            this.handler.matches("/foo/@v/list"),
            is(true)
        );
        assertThat(
            this.handler.matches("/github.com/foo/bar/@v/list"),
            is(true)
        );
        assertThat(
            this.handler.matches("/foo/@latest"),
            is(false)
        );
        // Root list with empty module must not match.
        assertThat(
            this.handler.matches("/@v/list"),
            is(false)
        );
    }

    @Test
    void passesThroughWhenNothingBlocked() throws Exception {
        final String body = "v1.0.0\nv1.1.0\nv1.2.0\n";
        this.upstream.put("/github.com/foo/bar/@v/list", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@v/list"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        assertThat(
            new String(bodyToBytes(resp), StandardCharsets.UTF_8),
            equalTo(body)
        );
    }

    @Test
    void filtersBlockedVersions() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.0.0\nv1.1.0\nv1.2.0\n"
        );
        this.cooldown.block("v1.1.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@v/list"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        assertThat(
            new String(bodyToBytes(resp), StandardCharsets.UTF_8),
            equalTo("v1.0.0\nv1.2.0\n")
        );
    }

    @Test
    void returnsForbiddenWhenAllVersionsBlocked() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.0.0\nv1.1.0\nv1.2.0\n"
        );
        this.cooldown.block("v1.0.0", "v1.1.0", "v1.2.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@v/list"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(403));
        final String body = new String(bodyToBytes(resp), StandardCharsets.UTF_8);
        assertThat(body, containsString("cooldown"));
    }

    @Test
    void forwardsUpstream404Unchanged() throws Exception {
        // No scripted body -> upstream returns 404.
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/missing/@v/list"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void passesThroughMalformedBodyAsBestEffort() throws Exception {
        // GoMetadataParser tolerates non-version lines — it just collects every
        // non-empty trimmed line as a "version". The handler therefore
        // evaluates and filters on that best-effort parse. With nothing in the
        // block set, the output round-trips the input.
        final String body = "v1.0.0\ngarbage-line\nv1.1.0\n";
        this.upstream.put("/github.com/foo/bar/@v/list", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@v/list"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        // Pass-through fast path: nothing blocked => upstream bytes verbatim.
        assertThat(
            new String(bodyToBytes(resp), StandardCharsets.UTF_8),
            equalTo(body)
        );
    }

    @Test
    void contentTypeIsPlainTextUtf8AfterFiltering() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.0.0\nv1.1.0\n"
        );
        this.cooldown.block("v1.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@v/list"),
            "alice"
        ).get();
        assertThat(
            contentType(resp),
            equalTo("text/plain; charset=utf-8")
        );
    }

    @Test
    void preservesTrailingNewlineWhenPresent() throws Exception {
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.0.0\nv1.1.0\n"
        );
        this.cooldown.block("v1.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@v/list"),
            "alice"
        ).get();
        final String out = new String(bodyToBytes(resp), StandardCharsets.UTF_8);
        assertThat(out, equalTo("v1.1.0\n"));
    }

    @Test
    void omitsTrailingNewlineWhenAbsentUpstream() throws Exception {
        // Upstream body does NOT end with a newline; round-trip must match.
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.0.0\nv1.1.0"
        );
        this.cooldown.block("v1.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@v/list"),
            "alice"
        ).get();
        final String out = new String(bodyToBytes(resp), StandardCharsets.UTF_8);
        assertThat(out, equalTo("v1.1.0"));
    }

    @Test
    void integrationBothHandlersCoexistOnSameRepo() throws Exception {
        // Seed three versions; block v1.1.0. /@v/list must drop it AND
        // /@latest must not fall back because its version (v1.2.0) is allowed.
        this.upstream.put(
            "/github.com/foo/bar/@v/list",
            "v1.0.0\nv1.1.0\nv1.2.0\n"
        );
        this.upstream.put(
            "/github.com/foo/bar/@latest",
            "{\"Version\":\"v1.2.0\",\"Time\":\"2024-05-12T00:00:00Z\"}"
        );
        this.cooldown.block("v1.1.0");
        final Response listResp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@v/list"),
            "alice"
        ).get();
        assertThat(
            new String(bodyToBytes(listResp), StandardCharsets.UTF_8),
            equalTo("v1.0.0\nv1.2.0\n")
        );
        final Response latestResp = this.latestHandler.handle(
            new RequestLine(RqMethod.GET, "/github.com/foo/bar/@latest"),
            "alice"
        ).get();
        assertThat(latestResp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(latestResp));
        assertThat(node.get("Version").asText(), equalTo("v1.2.0"));
    }

    // ===== Helpers =====

    private static byte[] bodyToBytes(final Response resp) throws Exception {
        return resp.body().asBytesFuture().get();
    }

    private static String contentType(final Response resp) {
        return StreamSupport.stream(resp.headers().spliterator(), false)
            .filter(h -> "Content-Type".equalsIgnoreCase(h.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse("");
    }

    /** Minimal scripted {@link Slice}: serves canned bodies for exact paths. */
    private static final class ScriptedSlice implements Slice {
        private final Map<String, byte[]> script = new HashMap<>();
        private final Map<String, AtomicInteger> hits = new HashMap<>();

        void put(final String path, final String body) {
            this.script.put(path, body.getBytes(StandardCharsets.UTF_8));
            this.hits.put(path, new AtomicInteger(0));
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
