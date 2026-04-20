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
import static org.hamcrest.Matchers.not;

/**
 * Integration-style tests for {@link PypiSimpleHandler}.
 *
 * @since 2.2.0
 */
final class PypiSimpleHandlerTest {

    private ScriptedSlice upstream;
    private ScriptedCooldown cooldown;
    private PypiSimpleHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        this.cooldown = new ScriptedCooldown();
        this.handler = new PypiSimpleHandler(
            this.upstream, this.cooldown, new NullInspector(),
            "pypi-proxy", "pypi-test"
        );
    }

    @Test
    void matchesSimplePathButNotJson() {
        assertThat(this.handler.matches("/simple/foo/"), is(true));
        assertThat(this.handler.matches("/simple/foo"), is(true));
        assertThat(this.handler.matches("/pypi/foo/json"), is(false));
        assertThat(this.handler.matches("/packages/foo-1.0.0.tar.gz"), is(false));
    }

    @Test
    void blockedVersionRemovedFromSimpleIndex() throws Exception {
        this.upstream.put(
            "/simple/foo/",
            simpleHtml("foo", "1.0.0", "1.1.0", "1.2.0")
        );
        this.cooldown.block("1.2.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/simple/foo/"), "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        final String body = new String(bodyBytes(resp), StandardCharsets.UTF_8);
        assertThat(body, containsString("foo-1.0.0.tar.gz"));
        assertThat(body, containsString("foo-1.1.0.tar.gz"));
        assertThat(body, not(containsString("foo-1.2.0.tar.gz")));
    }

    @Test
    void allBlockedReturns404() throws Exception {
        this.upstream.put(
            "/simple/foo/",
            simpleHtml("foo", "1.0.0", "1.1.0")
        );
        this.cooldown.block("1.0.0", "1.1.0");
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/simple/foo/"), "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    @Test
    void noBlockedVersionsForwardsUpstreamBytes() throws Exception {
        final String body = simpleHtml("foo", "1.0.0", "1.1.0");
        this.upstream.put("/simple/foo/", body);
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/simple/foo/"), "alice"
        ).get();
        assertThat(resp.status().success(), is(true));
        assertThat(
            new String(bodyBytes(resp), StandardCharsets.UTF_8),
            equalTo(body)
        );
    }

    @Test
    void upstream404ForwardedUnchanged() throws Exception {
        final Response resp = this.handler.handle(
            new RequestLine(RqMethod.GET, "/simple/missing/"), "alice"
        ).get();
        assertThat(resp.status().code(), equalTo(404));
    }

    // ===== Helpers =====

    private static byte[] bodyBytes(final Response resp) throws Exception {
        return resp.body().asBytesFuture().get();
    }

    /**
     * Build a minimal PEP 503 Simple Index HTML for {@code pkg} with
     * one {@code .tar.gz} link per version.
     */
    private static String simpleHtml(final String pkg, final String... versions) {
        final StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><body>\n");
        for (final String version : versions) {
            html.append("<a href=\"../../packages/").append(pkg).append('-')
                .append(version).append(".tar.gz#sha256=abc\">")
                .append(pkg).append('-').append(version)
                .append(".tar.gz</a>\n");
        }
        html.append("</body></html>");
        return html.toString();
    }

    /** Minimal scripted {@link Slice}. */
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

    /** No-op inspector. */
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
