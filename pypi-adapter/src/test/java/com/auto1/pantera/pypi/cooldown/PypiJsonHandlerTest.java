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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Integration-style tests for {@link PypiJsonHandler}. Uses an in-memory
 * scripted upstream and cooldown service, mirroring {@code GoListHandlerTest}.
 *
 * @since 2.2.0
 */
final class PypiJsonHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptedSlice upstream;
    private ScriptedCooldown cooldown;
    private PypiJsonHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new ScriptedCooldown();
        this.handler = new PypiJsonHandler(
            this.upstream, this.cooldown, new NullInspector(),
            "pypi-proxy", "pypi-test"
        );
    }

    @Test
    void matchesJsonPathButNotSimple() {
        assertThat(this.handler.matches("/pypi/foo/json"), is(true));
        assertThat(this.handler.matches("/simple/foo/"), is(false));
        assertThat(this.handler.matches("/pypi/foo/1.0.0/json"), is(false));
    }

    @Test
    void seededThreeVersionsBlockLatestFallsBackTo_1_1_0() throws Exception {
        // Scenario from the task spec: versions 1.0.0, 1.1.0, 1.2.0 with
        // 1.2.0 blocked; info.version must swap to 1.1.0 and urls must
        // be the files for 1.1.0.
        this.upstream.put(
            "/pypi/foo/json",
            pypiJson("1.2.0", "1.0.0", "1.1.0", "1.2.0")
        );
        this.cooldown.block("1.2.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/pypi/foo/json"), "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final JsonNode root = MAPPER.readTree(bodyBytes(resp));
        assertThat(root.get("info").get("version").asText(), equalTo("1.1.0"));
        assertThat(root.get("releases").has("1.0.0"), is(true));
        assertThat(root.get("releases").has("1.1.0"), is(true));
        assertThat(root.get("releases").has("1.2.0"), is(false));
        assertThat(root.get("urls").isArray(), is(true));
        assertThat(root.get("urls").size(), equalTo(1));
        assertThat(
            root.get("urls").get(0).get("filename").asText(),
            equalTo("foo-1.1.0.tar.gz")
        );
    }

    @Test
    void noBlockedVersionsPassesUpstreamThrough() throws Exception {
        final String body = pypiJson("2.32.0", "1.0.0", "2.32.0");
        this.upstream.put("/pypi/requests/json", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/pypi/requests/json"), "alice"
        ).get();
        final JsonNode root = MAPPER.readTree(bodyBytes(resp));
        assertThat(root.get("info").get("version").asText(), equalTo("2.32.0"));
        assertThat(root.get("releases").has("2.32.0"), is(true));
    }

    @Test
    void allVersionsBlockedReturns404() throws Exception {
        this.upstream.put(
            "/pypi/foo/json",
            pypiJson("1.1.0", "1.0.0", "1.1.0")
        );
        this.cooldown.block("1.0.0", "1.1.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/pypi/foo/json"), "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
        final String body = new String(bodyBytes(resp), StandardCharsets.UTF_8);
        assertThat(body, containsString("cooldown"));
    }

    @Test
    void upstream404ForwardedUnchanged() throws Exception {
        // No scripted body → scripted slice returns 404.
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/pypi/missing/json"), "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void malformedUpstreamPassesThrough() throws Exception {
        this.upstream.put("/pypi/foo/json", "not-json-at-all");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/pypi/foo/json"), "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        assertThat(
            new String(bodyBytes(resp), StandardCharsets.UTF_8),
            equalTo("not-json-at-all")
        );
    }

    @Test
    void normalisesPackageNameBeforeCooldownLookup() throws Exception {
        // PyPI tolerates non-canonical names in the URL but the publish-date
        // path stores entries under the PEP 503 canonical name. The handler
        // must normalize "Foo_Bar" -> "foo-bar" before consulting cooldown,
        // otherwise the inspector lookup misses the DB row and the filter
        // silently falls open.
        this.upstream.put(
            "/pypi/Foo_Bar/json",
            pypiJson("1.0.0", "1.0.0")
        );
        this.handler.handle(
            new RequestLine(RqMethod.GET, "/pypi/Foo_Bar/json"), "alice"
        ).get();
        assertThat(this.cooldown.lastArtifact(), equalTo("foo-bar"));
    }

    @Test
    void blockedNonLatestDroppedLatestUnchanged() throws Exception {
        this.upstream.put(
            "/pypi/foo/json",
            pypiJson("2.0.0", "1.0.0", "1.5.0", "2.0.0")
        );
        this.cooldown.block("1.5.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/pypi/foo/json"), "alice"
        ).get();
        final JsonNode root = MAPPER.readTree(bodyBytes(resp));
        // info.version 2.0.0 was not blocked — must stay.
        assertThat(root.get("info").get("version").asText(), equalTo("2.0.0"));
        assertThat(root.get("releases").has("1.5.0"), is(false));
    }

    // ===== Helpers =====

    private static byte[] bodyBytes(final Response resp) throws Exception {
        return resp.body().asBytesFuture().get();
    }

    /**
     * Build a minimal PyPI JSON API response. Each release gets one
     * deterministic file with {@code filename = pkg-<version>.tar.gz}.
     */
    private static String pypiJson(final String infoVersion, final String... versions) {
        final StringBuilder releases = new StringBuilder();
        releases.append("\"releases\":{");
        for (int idx = 0; idx < versions.length; idx++) {
            if (idx > 0) {
                releases.append(',');
            }
            releases.append('"').append(versions[idx]).append("\":[")
                .append(fileObject(versions[idx])).append(']');
        }
        releases.append('}');
        return "{"
            + "\"info\":{\"name\":\"foo\",\"version\":\"" + infoVersion + "\"},"
            + releases
            + ",\"urls\":[" + fileObject(infoVersion) + "]"
            + "}";
    }

    private static String fileObject(final String version) {
        return "{\"filename\":\"foo-" + version
            + ".tar.gz\",\"url\":\"https://files.pythonhosted.org/foo-"
            + version + ".tar.gz\"}";
    }

    /** Minimal scripted {@link Slice} — returns canned bodies for exact paths. */
    private static final class ScriptedSlice implements Slice {
        private final Map<String, byte[]> script = new HashMap<>();

        void put(final String path, final String body) {
            this.script.put(path, body.getBytes(StandardCharsets.UTF_8));
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

    /** Scripted cooldown service. */
    private static final class ScriptedCooldown implements CooldownService {
        private final Set<String> blocked = new HashSet<>();
        private volatile String lastArtifact;

        void block(final String... versions) {
            for (final String v : versions) {
                this.blocked.add(v);
            }
        }

        String lastArtifact() {
            return this.lastArtifact;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            this.lastArtifact = request.artifact();
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

    /** Inspector stub — returns no data. */
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
