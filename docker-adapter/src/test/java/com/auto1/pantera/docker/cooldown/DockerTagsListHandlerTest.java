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
package com.auto1.pantera.docker.cooldown;

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
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link DockerTagsListHandler} — orchestration layer that
 * filters cooldown-blocked tags out of
 * {@code /v2/<name>/tags/list} responses.
 *
 * <p>Structured like {@code GoListHandlerTest} and
 * {@code PypiSimpleHandlerTest}: an in-memory scripted {@link Slice}
 * returns canned bodies for exact paths, and a scripted
 * {@link CooldownService} marks a mutable set of tags as blocked.</p>
 *
 * @since 2.2.0
 */
final class DockerTagsListHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedSlice upstream;
    private ScriptedCooldown cooldown;
    private DockerTagsListHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new ScriptedCooldown();
        this.handler = new DockerTagsListHandler(
            this.upstream, this.cooldown, new NullInspector(),
            "docker-proxy", "docker-test"
        );
    }

    @Test
    void matchesTagsListButNotOtherPaths() {
        assertThat(
            this.handler.matches("/v2/library/nginx/tags/list"),
            is(true)
        );
        assertThat(
            this.handler.matches("/v2/myorg/myimage/tags/list"),
            is(true)
        );
        assertThat(
            this.handler.matches("/v2/library/nginx/manifests/latest"),
            is(false)
        );
        assertThat(
            this.handler.matches("/v2/library/nginx/blobs/sha256:abc"),
            is(false)
        );
        assertThat(
            this.handler.matches("/v2/_catalog"),
            is(false)
        );
    }

    @Test
    void passesThroughWhenNothingBlocked() throws Exception {
        final String body = tagsJson("library/nginx", "1.24", "1.25", "latest");
        this.upstream.put("/v2/library/nginx/tags/list", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/tags/list"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("name").asText(), equalTo("library/nginx"));
        assertThat(
            tagsOf(node),
            containsInAnyOrder("1.24", "1.25", "latest")
        );
    }

    @Test
    void filtersBlockedTag() throws Exception {
        final String body = tagsJson("library/nginx", "1.24", "1.25", "latest");
        this.upstream.put("/v2/library/nginx/tags/list", body);
        this.cooldown.block("latest");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/tags/list"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("name").asText(), equalTo("library/nginx"));
        assertThat(
            tagsOf(node),
            containsInAnyOrder("1.24", "1.25")
        );
    }

    @Test
    void allTagsBlockedReturnsEmptyArrayNot404() throws Exception {
        // Docker clients expect {"name":..., "tags":[]} — NOT 404 — when
        // the tags list is legitimately empty. Returning 404 confuses
        // tooling that treats 404 as "no such repo".
        final String body = tagsJson("library/nginx", "1.24", "1.25");
        this.upstream.put("/v2/library/nginx/tags/list", body);
        this.cooldown.block("1.24", "1.25");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/tags/list"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(200));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("name").asText(), equalTo("library/nginx"));
        assertThat(node.get("tags").isArray(), is(true));
        assertThat(node.get("tags").size(), equalTo(0));
    }

    @Test
    void forwardsUpstream404Unchanged() throws Exception {
        // No scripted body -> upstream returns 404.
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/missing/tags/list"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void preservesJsonContentType() throws Exception {
        final String body = tagsJson("library/nginx", "1.24", "1.25", "latest");
        this.upstream.put("/v2/library/nginx/tags/list", body);
        this.cooldown.block("latest");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/tags/list"),
            "alice"
        ).get();
        assertThat(contentType(resp), equalTo("application/json"));
    }

    @Test
    void preservesNameFieldAfterFiltering() throws Exception {
        // Regression check: the rewritten JSON must retain "name", not
        // just the tags array — Docker clients key display/logging on it.
        final String body = tagsJson("myorg/myimage", "v1", "v2", "v3");
        this.upstream.put("/v2/myorg/myimage/tags/list", body);
        this.cooldown.block("v2");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/myorg/myimage/tags/list"),
            "alice"
        ).get();
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("name").asText(), equalTo("myorg/myimage"));
    }

    @Test
    void malformedUpstreamBodyFallsThroughAsIs() throws Exception {
        // Empty body — DockerMetadataParser raises and we pass through.
        this.upstream.put("/v2/library/nginx/tags/list", "");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/tags/list"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        assertThat(bodyToBytes(resp).length, equalTo(0));
    }

    // ===== Helpers =====

    private static String tagsJson(final String name, final String... tags) {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(name).append("\",\"tags\":[");
        for (int idx = 0; idx < tags.length; idx++) {
            if (idx > 0) {
                sb.append(',');
            }
            sb.append('"').append(tags[idx]).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static Set<String> tagsOf(final JsonNode node) {
        final Set<String> out = new HashSet<>();
        node.get("tags").forEach(t -> out.add(t.asText()));
        return out;
    }

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

        void put(final String path, final String body) {
            this.script.put(path, body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            final byte[] content = this.script.get(path);
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

    /** Scripted {@link CooldownService}: flags listed tags as blocked. */
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

    /** Inspector that returns nothing. */
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
