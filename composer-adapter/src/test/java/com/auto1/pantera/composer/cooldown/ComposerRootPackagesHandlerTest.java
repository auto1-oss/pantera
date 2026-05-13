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
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ComposerRootPackagesHandler}.
 *
 * @since 2.2.0
 */
final class ComposerRootPackagesHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedSlice upstream;
    private ScriptedCooldown cooldown;
    private ComposerRootPackagesHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new ScriptedCooldown();
        this.handler = new ComposerRootPackagesHandler(
            this.upstream, this.cooldown, new NullInspector(),
            "php", "composer-test"
        );
    }

    @Test
    void matchesRootPaths() {
        assertThat(this.handler.matches("/packages.json"), is(true));
        assertThat(this.handler.matches("/repo.json"), is(true));
        assertThat(this.handler.matches("/p2/acme/foo.json"), is(false));
        assertThat(this.handler.matches("/packages/acme/foo.json"), is(false));
        assertThat(this.handler.matches("/"), is(false));
    }

    @Test
    void passesThroughLazyProvidersScheme() throws Exception {
        final String body = """
            {
              "packages": [],
              "providers-url": "/p/%package%$%hash%.json",
              "metadata-url": "/p2/%package%.json",
              "notify-batch": "/downloads/"
            }
            """;
        this.upstream.put("/packages.json", body);
        // Even if we "block" everything, lazy scheme is untouched.
        this.cooldown.blockPair("acme/foo", "1.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("metadata-url").asText(),
            equalTo("/p2/%package%.json"));
        assertThat(node.get("notify-batch").asText(), equalTo("/downloads/"));
    }

    @Test
    void filtersInlineBlockedVersion() throws Exception {
        final String body = """
            {
              "packages": {
                "acme/foo": {
                  "1.0.0": {"name": "acme/foo", "version": "1.0.0"},
                  "2.0.0": {"name": "acme/foo", "version": "2.0.0"}
                },
                "acme/bar": {
                  "3.0.0": {"name": "acme/bar", "version": "3.0.0"}
                }
              },
              "notify-batch": "/downloads/"
            }
            """;
        this.upstream.put("/packages.json", body);
        this.cooldown.blockPair("acme/foo", "2.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        final JsonNode foo = node.get("packages").get("acme/foo");
        assertThat(foo.has("1.0.0"), is(true));
        assertThat(foo.has("2.0.0"), is(false));
        // acme/bar untouched.
        assertThat(
            node.get("packages").get("acme/bar").has("3.0.0"),
            is(true)
        );
        // Top-level metadata preserved.
        assertThat(node.get("notify-batch").asText(), equalTo("/downloads/"));
    }

    @Test
    void dropsPackageWhenAllVersionsBlocked() throws Exception {
        final String body = """
            {
              "packages": {
                "acme/foo": {
                  "1.0.0": {}, "2.0.0": {}
                },
                "acme/bar": {
                  "3.0.0": {}
                }
              }
            }
            """;
        this.upstream.put("/packages.json", body);
        this.cooldown.blockPair("acme/foo", "1.0.0");
        this.cooldown.blockPair("acme/foo", "2.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("packages").has("acme/foo"), is(false));
        assertThat(node.get("packages").has("acme/bar"), is(true));
    }

    @Test
    void handlesRepoJsonEndpoint() throws Exception {
        final String body = """
            {
              "packages": {
                "acme/foo": {"1.0.0": {}, "2.0.0": {}}
              }
            }
            """;
        this.upstream.put("/repo.json", body);
        this.cooldown.blockPair("acme/foo", "2.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/repo.json"),
            "alice"
        ).get();
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(
            node.get("packages").get("acme/foo").has("1.0.0"), is(true)
        );
        assertThat(
            node.get("packages").get("acme/foo").has("2.0.0"), is(false)
        );
    }

    @Test
    void passesThroughWhenNothingBlocked() throws Exception {
        final String body = """
            {
              "packages": {
                "acme/foo": {"1.0.0": {}, "2.0.0": {}}
              }
            }
            """;
        this.upstream.put("/packages.json", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(
            node.get("packages").get("acme/foo").has("1.0.0"), is(true)
        );
        assertThat(
            node.get("packages").get("acme/foo").has("2.0.0"), is(true)
        );
    }

    @Test
    void forwardsUpstream404Unchanged() throws Exception {
        // Nothing scripted → 404.
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void forwardsUpstream500Unchanged() throws Exception {
        this.upstream.put500("/packages.json");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(500));
    }

    @Test
    void passesThroughMalformedJson() throws Exception {
        this.upstream.put("/packages.json", "garbled{invalid");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        assertThat(
            new String(bodyToBytes(resp), StandardCharsets.UTF_8),
            equalTo("garbled{invalid")
        );
    }

    @Test
    void preservesContentType() throws Exception {
        final String body = """
            {"packages": {"acme/foo": {"1.0.0": {}}}}
            """;
        this.upstream.put("/packages.json", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        assertThat(
            contentType(resp).toLowerCase(),
            is("application/json")
        );
    }

    @Test
    void allBlockedRootStillReturns200WithEmptyPackages() throws Exception {
        // Root aggregation: diverges from per-package — we return 200
        // even when everything is blocked, because the Composer
        // repository spec allows empty packages. A 404 at the root
        // would be interpreted as "repository doesn't exist" and
        // confuse client tooling.
        final String body = """
            {
              "packages": {
                "acme/foo": {"1.0.0": {}}
              },
              "metadata-url": "/p2/%package%.json"
            }
            """;
        this.upstream.put("/packages.json", body);
        this.cooldown.blockPair("acme/foo", "1.0.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/packages.json"),
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(200));
        final JsonNode node = MAPPER.readTree(bodyToBytes(resp));
        assertThat(node.get("packages").size(), equalTo(0));
        assertThat(node.get("metadata-url").asText(),
            equalTo("/p2/%package%.json"));
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

    /** Scripted cooldown: blocks specific (pkg, version) pairs. */
    private static final class ScriptedCooldown implements CooldownService {
        private final Set<Map.Entry<String, String>> blocked = new HashSet<>();

        void blockPair(final String pkg, final String version) {
            this.blocked.add(
                new AbstractMap.SimpleEntry<>(pkg, version)
            );
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            final Map.Entry<String, String> key = new AbstractMap.SimpleEntry<>(
                request.artifact(), request.version()
            );
            if (!this.blocked.contains(key)) {
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
