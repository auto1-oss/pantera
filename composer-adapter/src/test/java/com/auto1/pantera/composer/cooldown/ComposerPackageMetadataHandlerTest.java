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
package com.auto1.pantera.composer.cooldown;

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
 * Tests for {@link ComposerPackageMetadataHandler}.
 *
 * <p>Structured like {@code DockerTagsListHandlerTest} /
 * {@code PypiSimpleHandlerTest}: an in-memory scripted {@link Slice}
 * returns canned bodies for exact paths, and a scripted
 * {@link CooldownService} marks a mutable set of versions as
 * blocked.</p>
 *
 * @since 2.2.0
 */
final class ComposerPackageMetadataHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedSlice upstream;
    private ScriptedCooldown cooldown;
    private ComposerPackageMetadataHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new ScriptedCooldown();
        this.handler = new ComposerPackageMetadataHandler(
            this.upstream, this.cooldown, new NullInspector(),
            "php", "composer-test"
        );
    }

    @Test
    void matchesP2AndPackagesPerPackage() {
        assertThat(
            this.handler.matches("/p2/acme/foo.json"),
            is(true)
        );
        assertThat(
            this.handler.matches("/packages/acme/foo.json"),
            is(true)
        );
        assertThat(
            this.handler.matches("/packages.json"),
            is(false)
        );
        assertThat(
            this.handler.matches("/repo.json"),
            is(false)
        );
        assertThat(
            this.handler.matches("/dist/acme/foo/1.0.0/foo-1.0.0.zip"),
            is(false)
        );
    }

    @Test
    void passesThroughWhenNothingBlocked() throws Exception {
        final String body = perPackageJson("acme/foo", "1.0.0", "1.1.0", "2.0.0");
        this.upstream.put("/p2/acme/foo.json", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(
            versionsOf(node, "acme/foo"),
            containsInAnyOrder("1.0.0", "1.1.0", "2.0.0")
        );
        assertThat(
            contentType(resp).toLowerCase(),
            is("application/json")
        );
    }

    @Test
    void dropsBlockedVersion() throws Exception {
        final String body = perPackageJson("acme/foo", "1.0.0", "1.1.0", "2.0.0");
        this.upstream.put("/p2/acme/foo.json", body);
        this.cooldown.block("2.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(
            versionsOf(node, "acme/foo"),
            containsInAnyOrder("1.0.0", "1.1.0")
        );
    }

    @Test
    void returns404WhenAllVersionsBlocked() throws Exception {
        final String body = perPackageJson("acme/foo", "1.0.0", "1.1.0", "2.0.0");
        this.upstream.put("/p2/acme/foo.json", body);
        this.cooldown.block("1.0.0", "1.1.0", "2.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void forwardsUpstream404Unchanged() throws Exception {
        // Nothing scripted at the path → ScriptedSlice returns 404.
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/unknown.json"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void forwardsUpstream500Unchanged() throws Exception {
        this.upstream.put500("/p2/acme/foo.json");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(500));
    }

    @Test
    void passesThroughMalformedJson() throws Exception {
        this.upstream.put("/p2/acme/foo.json", "not-json-at-all");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        assertThat(
            new String(bodyToBytes(resp), StandardCharsets.UTF_8),
            equalTo("not-json-at-all")
        );
    }

    @Test
    void handlesPackagesV1Endpoint() throws Exception {
        final String body = perPackageJson("acme/foo", "1.0.0", "2.0.0");
        this.upstream.put("/packages/acme/foo.json", body);
        this.cooldown.block("2.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages/acme/foo.json"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(
            versionsOf(node, "acme/foo"),
            containsInAnyOrder("1.0.0")
        );
    }

    @Test
    void preservesContentType() throws Exception {
        final String body = perPackageJson("acme/foo", "1.0.0");
        this.upstream.put("/p2/acme/foo.json", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"),
            "alice"
        ).get();
        assertThat(
            contentType(resp).toLowerCase(),
            is("application/json")
        );
    }

    @Test
    void preservesPerVersionMetadataFields() throws Exception {
        final String body = """
            {
              "packages": {
                "acme/foo": {
                  "1.0.0": {
                    "name": "acme/foo",
                    "version": "1.0.0",
                    "require": {"php": ">=8.1"},
                    "dist": {"url": "https://example.com/a.zip"}
                  },
                  "2.0.0": {
                    "name": "acme/foo",
                    "version": "2.0.0"
                  }
                }
              }
            }
            """;
        this.upstream.put("/p2/acme/foo.json", body);
        this.cooldown.block("2.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"),
            "alice"
        ).get();
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        final JsonNode kept = node.get("packages").get("acme/foo").get("1.0.0");
        assertThat(kept.get("require").get("php").asText(), equalTo(">=8.1"));
        assertThat(kept.get("dist").get("url").asText(),
            equalTo("https://example.com/a.zip"));
    }

    @Test
    void emptyVersionMapPassesThrough() throws Exception {
        final String body = """
            {"packages": {"acme/foo": {}}}
            """;
        this.upstream.put("/p2/acme/foo.json", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("packages").get("acme/foo").size(), equalTo(0));
    }

    @Test
    void nestedPathsAreRejected() {
        assertThat(
            this.handler.matches("/packages/vendor/package/extra.json"),
            is(false)
        );
        assertThat(
            this.handler.matches("/p2/vendor"),
            is(false)
        );
    }

    // ===== Helpers =====

    private static String perPackageJson(final String name, final String... versions) {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\"packages\":{\"").append(name).append("\":{");
        for (int idx = 0; idx < versions.length; idx++) {
            if (idx > 0) {
                sb.append(',');
            }
            final String v = versions[idx];
            sb.append('"').append(v).append("\":{\"name\":\"").append(name)
                .append("\",\"version\":\"").append(v).append("\"}");
        }
        sb.append("}}}");
        return sb.toString();
    }

    private static Set<String> versionsOf(final JsonNode node, final String pkg) {
        final Set<String> out = new HashSet<>();
        final JsonNode vers = node.get("packages").get(pkg);
        if (vers != null && vers.isObject()) {
            vers.fieldNames().forEachRemaining(out::add);
        }
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

    /** Minimal scripted slice: serves canned bodies for exact paths. */
    private static final class ScriptedSlice implements Slice {
        private final Map<String, byte[]> script = new HashMap<>();
        private final Set<String> error500 = new HashSet<>();

        void put(final String path, final String body) {
            this.script.put(path, body.getBytes(StandardCharsets.UTF_8));
        }

        void put500(final String path) {
            this.error500.add(path);
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            if (this.error500.contains(path)) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.internalError().build()
                );
            }
            final byte[] content = this.script.get(path);
            if (content == null) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", "application/json")
                    .body(content)
                    .build()
            );
        }
    }

    /** Scripted cooldown service: flags listed versions as blocked. */
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
