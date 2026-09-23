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
package com.auto1.pantera.composer;

import com.auto1.pantera.adapters.php.ComposerGroupSlice;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.group.SliceResolver;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests Phase 9 sequential-first behaviour of {@link ComposerGroupSlice}
 * for {@code packages.json}: first member to return 200 wins; later members
 * are NEVER queried; non-OK members fall through to the next.
 */
public final class ComposerGroupSliceTest {

    @Test
    void packagesJsonSequentialFirstSkipsLaterMembersOn200() throws Exception {
        final AtomicInteger m1Calls = new AtomicInteger(0);
        final AtomicInteger m2Calls = new AtomicInteger(0);

        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", new CountingSlice(m1Calls, jsonOk(
            "{\"packages\":{\"vendor/pkg1\":{\"1.0\":{\"name\":\"vendor/pkg1\","
                + "\"version\":\"1.0\"}}},\"metadata-url\":\"https://upstream.example/p2/%package%.json\"}"
        )));
        members.put("repo2", new CountingSlice(m2Calls, jsonOk(
            "{\"packages\":{\"vendor/pkg2\":{\"2.0\":{\"name\":\"vendor/pkg2\","
                + "\"version\":\"2.0\"}}}}"
        )));

        final ComposerGroupSlice slice = new ComposerGroupSlice(
            new FakeDelegate(),
            new MapResolver(members),
            "php-group",
            List.of("repo1", "repo2"),
            8080,
            ""
        );

        final Response resp = slice.response(
            new RequestLine("GET", "/packages.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Response is OK",
            resp.status(), Matchers.equalTo(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "First member queried exactly once",
            m1Calls.get(), Matchers.equalTo(1)
        );
        MatcherAssert.assertThat(
            "Second member NEVER queried (sequential-first wins)",
            m2Calls.get(), Matchers.equalTo(0)
        );

        final String body = new String(resp.body().asBytes(), StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "Body contains repo1's package",
            body, Matchers.containsString("vendor/pkg1")
        );
        MatcherAssert.assertThat(
            "Body does NOT contain repo2's package (no merge)",
            body, Matchers.not(Matchers.containsString("vendor/pkg2"))
        );
        MatcherAssert.assertThat(
            "metadata-url rewritten to group basePath",
            body, Matchers.containsString("/php-group/p2/%package%.json")
        );
        MatcherAssert.assertThat(
            "Upstream metadata-url stripped",
            body, Matchers.not(Matchers.containsString("upstream.example"))
        );
    }

    @Test
    void packagesJsonFallsThroughOn404() throws Exception {
        final AtomicInteger m1Calls = new AtomicInteger(0);
        final AtomicInteger m2Calls = new AtomicInteger(0);

        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", new CountingSlice(m1Calls, status(RsStatus.NOT_FOUND)));
        members.put("repo2", new CountingSlice(m2Calls, jsonOk(
            "{\"packages\":{\"vendor/win\":{\"9.9\":{\"name\":\"vendor/win\","
                + "\"version\":\"9.9\"}}}}"
        )));

        final ComposerGroupSlice slice = new ComposerGroupSlice(
            new FakeDelegate(),
            new MapResolver(members),
            "php-group",
            List.of("repo1", "repo2"),
            8080,
            ""
        );

        final Response resp = slice.response(
            new RequestLine("GET", "/packages.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Response is OK (member 2 answered)",
            resp.status(), Matchers.equalTo(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Member 1 queried exactly once",
            m1Calls.get(), Matchers.equalTo(1)
        );
        MatcherAssert.assertThat(
            "Member 2 queried exactly once after 404 fall-through",
            m2Calls.get(), Matchers.equalTo(1)
        );
        final String body = new String(resp.body().asBytes(), StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "Body contains member 2's package (single winner, no merge)",
            body, Matchers.containsString("vendor/win")
        );
    }

    @Test
    void packagesJson404WhenAllMembersFail() throws Exception {
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", status(RsStatus.NOT_FOUND));
        members.put("repo2", status(RsStatus.NOT_FOUND));

        final ComposerGroupSlice slice = new ComposerGroupSlice(
            new FakeDelegate(),
            new MapResolver(members),
            "php-group",
            List.of("repo1", "repo2"),
            8080,
            ""
        );

        final Response resp = slice.response(
            new RequestLine("GET", "/packages.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "All-404 collapses to 404",
            resp.status(), Matchers.equalTo(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void p2CooldownVerdictIsRelayedVerbatim() throws Exception {
        final AtomicInteger later = new AtomicInteger(0);
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", (line, headers, body) -> body.asBytesFuture().thenApply(
            ignored -> ResponseBuilder.notFound()
                .header("X-Pantera-Cooldown", "all-blocked")
                .textBody("All versions of 'acme/foo' are under cooldown; no versions available.")
                .build()
        ));
        members.put("repo2", new CountingSlice(later, jsonOk(
            "{\"packages\":{\"acme/foo\":[{\"name\":\"acme/foo\",\"version\":\"9.9\"}]}}"
        )));
        final Response resp = new ComposerGroupSlice(
            new FakeDelegate(), new MapResolver(members), "php-group",
            List.of("repo1", "repo2"), 8080, ""
        ).response(
            new RequestLine("GET", "/p2/acme/foo.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "status of the verdict kept", resp.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "cooldown marker kept",
            resp.headers().values("X-Pantera-Cooldown"), new IsEqual<>(List.of("all-blocked"))
        );
        MatcherAssert.assertThat(
            "reason body kept",
            new String(resp.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("All versions of 'acme/foo' are under cooldown; no versions available.")
        );
        MatcherAssert.assertThat(
            "the verdict is authoritative: later members are not asked",
            later.get(), new IsEqual<>(0)
        );
    }

    @Test
    void p2GenuineMissFallsThroughToTheNextMember() throws Exception {
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", status(RsStatus.NOT_FOUND));
        members.put("repo2", jsonOk("{\"packages\":{\"acme/foo\":[]}}"));
        final Response resp = new ComposerGroupSlice(
            new FakeDelegate(), new MapResolver(members), "php-group",
            List.of("repo1", "repo2"), 8080, ""
        ).response(
            new RequestLine("GET", "/p2/acme/foo.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.OK));
    }

    @Test
    void devFileOfALocallyOwnedPackageNeverComesFromAProxy() throws Exception {
        final AtomicInteger proxyCalls = new AtomicInteger(0);
        final Map<String, Slice> members = new HashMap<>();
        members.put("local", ComposerGroupSliceTest.byPath(Map.of(
            "/local/p2/acme/private.json",
            "{\"packages\":{\"acme/private\":{\"dev-master\":{\"version\":\"dev-master\"}}}}"
        )));
        members.put("proxy", new CountingSlice(proxyCalls, jsonOk(
            "{\"packages\":{\"acme/private\":[{\"version\":\"dev-feature\"}]}}"
        )));
        final Response resp = ComposerGroupSliceTest.group(members, "local", "proxy").response(
            new RequestLine("GET", "/p2/acme/private~dev.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "no proxy versions for a package owned by a local member",
            resp.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the private package name is never sent upstream",
            proxyCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void localMemberOwnsAPackageEvenWhenAProxyIsDeclaredFirst() throws Exception {
        final AtomicInteger proxyCalls = new AtomicInteger(0);
        final Map<String, Slice> members = new HashMap<>();
        members.put("proxy", new CountingSlice(proxyCalls, jsonOk(
            "{\"packages\":{\"acme/private\":[{\"version\":\"9.9.9\"}]}}"
        )));
        members.put("local", ComposerGroupSliceTest.byPath(Map.of(
            "/local/p2/acme/private.json",
            "{\"packages\":{\"acme/private\":{\"1.0.0\":{\"version\":\"1.0.0\"}}}}"
        )));
        final Response resp = ComposerGroupSliceTest.group(members, "proxy", "local").response(
            new RequestLine("GET", "/p2/acme/private.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the local metadata is served",
            new String(resp.body().asBytes(), StandardCharsets.UTF_8),
            Matchers.containsString("1.0.0")
        );
        MatcherAssert.assertThat(
            "the proxy is not asked for a locally owned name",
            proxyCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void packageUnknownLocallyIsResolvedThroughTheProxy() throws Exception {
        final Map<String, Slice> members = new HashMap<>();
        members.put("local", status(RsStatus.NOT_FOUND));
        members.put("proxy", jsonOk("{\"packages\":{\"psr/log\":[{\"version\":\"3.0.0\"}]}}"));
        final Response resp = ComposerGroupSliceTest.group(members, "local", "proxy").response(
            new RequestLine("GET", "/p2/psr/log~dev.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.OK));
    }

    @Test
    void failingLocalMemberKeepsTheNameAwayFromProxies() throws Exception {
        final AtomicInteger proxyCalls = new AtomicInteger(0);
        final Map<String, Slice> members = new HashMap<>();
        members.put("local", status(RsStatus.INTERNAL_ERROR));
        members.put("proxy", new CountingSlice(proxyCalls, jsonOk("{\"packages\":{}}")));
        final Response resp = ComposerGroupSliceTest.group(members, "local", "proxy").response(
            new RequestLine("GET", "/p2/acme/private.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "ownership cannot be ruled out: unavailable, not missing",
            resp.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "the proxy is not asked while ownership is unknown",
            proxyCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void p2UpstreamOutageIsServiceUnavailableWithRetryAfter() throws Exception {
        final Map<String, Slice> members = new HashMap<>();
        members.put("local", status(RsStatus.NOT_FOUND));
        members.put("proxy", status(RsStatus.BAD_GATEWAY));
        final Response resp = ComposerGroupSliceTest.group(members, "local", "proxy").response(
            new RequestLine("GET", "/p2/psr/down.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "an outage is not a missing package",
            resp.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "clients are told when to retry",
            resp.headers().values("Retry-After").isEmpty(), new IsEqual<>(false)
        );
    }

    @Test
    void packagesJsonOutageIsServiceUnavailable() throws Exception {
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", status(RsStatus.NOT_FOUND));
        members.put("repo2", status(RsStatus.BAD_GATEWAY));
        final Response resp = ComposerGroupSliceTest.group(members, "repo1", "repo2").response(
            new RequestLine("GET", "/packages.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void memberForbiddenIsRelayedForP2() throws Exception {
        final Map<String, Slice> members = new HashMap<>();
        members.put("local", status(RsStatus.FORBIDDEN));
        members.put("proxy", status(RsStatus.FORBIDDEN));
        final Response resp = ComposerGroupSliceTest.group(members, "local", "proxy").response(
            new RequestLine("GET", "/p2/acme/lib.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.FORBIDDEN));
    }

    @Test
    void memberForbiddenIsRelayedForPackagesJson() throws Exception {
        final Map<String, Slice> members = new HashMap<>();
        members.put("repo1", status(RsStatus.FORBIDDEN));
        members.put("repo2", status(RsStatus.FORBIDDEN));
        final Response resp = ComposerGroupSliceTest.group(members, "repo1", "repo2").response(
            new RequestLine("GET", "/packages.json"), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.FORBIDDEN));
    }

    /**
     * Group whose second member is a proxy only when its name is "proxy".
     */
    private static ComposerGroupSlice group(
        final Map<String, Slice> members, final String first, final String second
    ) {
        return new ComposerGroupSlice(
            new FakeDelegate(), new MapResolver(members), "php-group",
            List.of(first, second), 8080, "",
            java.util.Set.of("proxy")
        );
    }

    /**
     * Member answering 200 with the given JSON for exact paths, 404 otherwise.
     */
    private static Slice byPath(final Map<String, String> bodies) {
        return (line, headers, body) -> body.asBytesFuture().thenApply(ignored -> {
            final String json = bodies.get(line.uri().getPath());
            if (json == null) {
                return ResponseBuilder.notFound().build();
            }
            return ResponseBuilder.ok()
                .header("Content-Type", "application/json")
                .body(json.getBytes(StandardCharsets.UTF_8))
                .build();
        });
    }

    private static Slice jsonOk(final String json) {
        return (line, headers, body) -> body.asBytesFuture().thenApply(ignored ->
            ResponseBuilder.ok()
                .header("Content-Type", "application/json")
                .body(json.getBytes(StandardCharsets.UTF_8))
                .build()
        );
    }

    private static Slice status(final RsStatus status) {
        return (line, headers, body) -> body.asBytesFuture().thenApply(ignored ->
            ResponseBuilder.from(status).build()
        );
    }

    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> map;
        private MapResolver(final Map<String, Slice> map) { this.map = map; }
        @Override
        public Slice slice(final Key name, final int port, final int depth) {
            return map.get(name.string());
        }
    }

    private static final class CountingSlice implements Slice {
        private final AtomicInteger counter;
        private final Slice delegate;
        private CountingSlice(final AtomicInteger counter, final Slice delegate) {
            this.counter = counter;
            this.delegate = delegate;
        }
        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            counter.incrementAndGet();
            return delegate.response(line, headers, body);
        }
    }

    private static final class FakeDelegate implements Slice {
        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            return body.asBytesFuture().thenApply(ignored ->
                ResponseBuilder.notFound().build()
            );
        }
    }
}
