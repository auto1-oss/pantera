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
import com.auto1.pantera.http.headers.Header;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link DockerManifestByTagHandler} — filters
 * {@code GET /v2/<name>/manifests/<tag>} against cooldown blocks on
 * both the tag string and the resolved {@code Docker-Content-Digest}.
 *
 * @since 2.2.0
 */
final class DockerManifestByTagHandlerTest {

    private static final String DIGEST_HEADER = "Docker-Content-Digest";

    private static final String FAKE_DIGEST =
        "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    private ScriptedSlice upstream;
    private ScriptedCooldown cooldown;
    private DockerManifestByTagHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new ScriptedCooldown();
        this.handler = new DockerManifestByTagHandler(
            this.upstream, this.cooldown, new NullInspector(),
            "docker-proxy", "docker-test"
        );
    }

    @Test
    void passesThroughWhenNothingBlocked() throws Exception {
        this.upstream.putManifest(
            "/v2/library/nginx/manifests/latest",
            FAKE_DIGEST,
            "{\"schemaVersion\":2}"
        );
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/manifests/latest"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(200));
        assertThat(
            new String(bodyBytes(resp), StandardCharsets.UTF_8),
            equalTo("{\"schemaVersion\":2}")
        );
        assertThat(headerValue(resp, DIGEST_HEADER), equalTo(FAKE_DIGEST));
    }

    @Test
    void digestBlockedReturns404ManifestUnknown() throws Exception {
        this.upstream.putManifest(
            "/v2/library/nginx/manifests/latest",
            FAKE_DIGEST,
            "{\"schemaVersion\":2}"
        );
        // Block BY DIGEST — the tag "latest" itself is not in the block
        // set, but the digest the tag resolves to is.
        this.cooldown.block(FAKE_DIGEST);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/manifests/latest"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
        assertErrorBody(resp, "latest");
    }

    @Test
    void tagBlockedReturns404ManifestUnknown() throws Exception {
        this.upstream.putManifest(
            "/v2/library/nginx/manifests/latest",
            FAKE_DIGEST,
            "{\"schemaVersion\":2}"
        );
        // Block BY TAG string.
        this.cooldown.block("latest");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/manifests/latest"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
        assertErrorBody(resp, "latest");
    }

    @Test
    void bothBlockedReturns404Once() throws Exception {
        this.upstream.putManifest(
            "/v2/library/nginx/manifests/latest",
            FAKE_DIGEST,
            "{\"schemaVersion\":2}"
        );
        this.cooldown.block("latest", FAKE_DIGEST);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/manifests/latest"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
        assertErrorBody(resp, "latest");
    }

    @Test
    void upstream404PassesThroughUnchanged() throws Exception {
        // No seeded manifest → upstream returns 404.
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/missing/manifests/latest"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void missingDigestHeaderFallsBackToTagOnly() throws Exception {
        // Some registries / proxies omit the digest header. Handler must
        // not drop the response just because the header is missing — it
        // falls back to tag-only checking.
        this.upstream.putManifestNoDigest(
            "/v2/library/nginx/manifests/stable",
            "{\"schemaVersion\":2}"
        );
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/manifests/stable"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(200));
    }

    @Test
    void missingDigestHeaderStillBlocksWhenTagBlocked() throws Exception {
        this.upstream.putManifestNoDigest(
            "/v2/library/nginx/manifests/stable",
            "{\"schemaVersion\":2}"
        );
        this.cooldown.block("stable");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/manifests/stable"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void preservesUpstreamHeadersOnPassThrough() throws Exception {
        this.upstream.putManifest(
            "/v2/library/nginx/manifests/latest",
            FAKE_DIGEST,
            "{\"schemaVersion\":2}"
        );
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/manifests/latest"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        // Docker-Content-Digest and Content-Type must be round-tripped.
        assertThat(headerValue(resp, DIGEST_HEADER), equalTo(FAKE_DIGEST));
        assertThat(
            headerValue(resp, "Content-Type"),
            containsString("manifest")
        );
    }

    @Test
    void errorBodyIsValidJsonAndJsonContentType() throws Exception {
        this.upstream.putManifest(
            "/v2/library/nginx/manifests/latest",
            FAKE_DIGEST,
            "{\"schemaVersion\":2}"
        );
        this.cooldown.block("latest");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/v2/library/nginx/manifests/latest"),
            Headers.EMPTY,
            Content.EMPTY,
            "alice"
        ).get();
        assertThat(headerValue(resp, "Content-Type"), containsString("json"));
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode body = mapper.readTree(bodyBytes(resp));
        assertThat(body.get("errors").isArray(), is(true));
        assertThat(body.get("errors").size(), equalTo(1));
        final JsonNode err = body.get("errors").get(0);
        assertThat(err.get("code").asText(), equalTo("MANIFEST_UNKNOWN"));
        assertThat(err.get("detail").get("Tag").asText(), equalTo("latest"));
    }

    // ===== Helpers =====

    private static void assertErrorBody(
        final Response resp, final String tag
    ) throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode body = mapper.readTree(bodyBytes(resp));
        assertThat(
            body.get("errors").get(0).get("code").asText(),
            equalTo("MANIFEST_UNKNOWN")
        );
        assertThat(
            body.get("errors").get(0).get("detail").get("Tag").asText(),
            equalTo(tag)
        );
    }

    private static byte[] bodyBytes(final Response resp) throws Exception {
        return resp.body().asBytesFuture().get();
    }

    private static String headerValue(final Response resp, final String name) {
        return StreamSupport.stream(resp.headers().spliterator(), false)
            .filter(h -> name.equalsIgnoreCase(h.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse("");
    }

    /**
     * Scripted upstream. {@code putManifest} attaches a
     * {@code Docker-Content-Digest} and standard v2 manifest
     * {@code Content-Type}; {@code putManifestNoDigest} omits the digest
     * header for the fallback-behaviour test.
     */
    private static final class ScriptedSlice implements Slice {
        private final Map<String, byte[]> bodies = new HashMap<>();
        private final Map<String, Headers> headers = new HashMap<>();

        void putManifest(
            final String path, final String digest, final String body
        ) {
            this.bodies.put(path, body.getBytes(StandardCharsets.UTF_8));
            this.headers.put(path, Headers.from(
                new Header(DIGEST_HEADER, digest),
                new Header("Content-Type",
                    "application/vnd.docker.distribution.manifest.v2+json")
            ));
        }

        void putManifestNoDigest(final String path, final String body) {
            this.bodies.put(path, body.getBytes(StandardCharsets.UTF_8));
            this.headers.put(path, Headers.from(
                new Header("Content-Type",
                    "application/vnd.docker.distribution.manifest.v2+json")
            ));
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers reqHeaders, final Content body
        ) {
            final String path = line.uri().getPath();
            final byte[] content = this.bodies.get(path);
            if (content == null) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .headers(this.headers.get(path))
                    .body(content)
                    .build()
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
