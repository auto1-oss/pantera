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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.LocalRefusal;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.TrimPathSlice;
import com.auto1.pantera.maven.http.UploadSlice;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A group remembers that no member has a {@code maven-metadata.xml}, by the
 * rules {@code GroupResolver} applies to artifacts: only a walk in which
 * every member answered an authoritative 404 is remembered, and the memory
 * is dropped the moment the metadata can exist.
 */
@Timeout(30)
final class MavenGroupMetadataMissTest {

    /**
     * Artifact-level metadata path.
     */
    private static final String PATH = "/com/acme/widget/maven-metadata.xml";

    /**
     * Snapshot-level metadata path of the same artifact.
     */
    private static final String SNAPSHOT = "/com/acme/widget/1.0-SNAPSHOT/maven-metadata.xml";

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        FilteredMetadataCacheRegistry.instance().clear();
    }

    @Test
    void secondMissDoesNotAskTheMembers() throws Exception {
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger second = new AtomicInteger();
        final MavenGroupSlice group = group(
            "miss-twice",
            Map.of(
                "a", new Counting(first, new Fixed(RsStatus.NOT_FOUND)),
                "b", new Counting(second, new Fixed(RsStatus.NOT_FOUND))
            ),
            "a", "b"
        );
        MatcherAssert.assertThat(
            "first request is a 404", get(group, PATH).status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        final Response again = get(group, PATH);
        MatcherAssert.assertThat(
            "second request is a 404", again.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the remembered 404 is authoritative for an outer group",
            again.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "member a was asked once", first.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "member b was asked once", second.get(), new IsEqual<>(1)
        );
    }

    @Test
    void checksumOfRememberedMissDoesNotAskTheMembers() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "miss-sidecar",
            Map.of("a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND))),
            "a"
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "sidecar of missing metadata is a 404",
            get(group, PATH + ".sha1").status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "member was asked once", calls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void publishToHostedMemberIsServedImmediately() throws Exception {
        final Hosted hosted = new Hosted("hosted");
        final MavenGroupSlice group = group(
            "deploy", Map.of("hosted", hosted, "proxy", new Fixed(RsStatus.NOT_FOUND)),
            "hosted", "proxy"
        );
        // mvn deploy: the artifact files first, then GET metadata (404),
        // then PUT the metadata, snapshot-level and artifact-level alike.
        hosted.put("/com/acme/widget/1.0-SNAPSHOT/widget-1.0-20260101.000000-1.jar", "jar");
        MatcherAssert.assertThat(
            "snapshot metadata is missing before deploy",
            get(group, SNAPSHOT).status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "artifact metadata is missing before deploy",
            get(group, PATH).status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        hosted.put(SNAPSHOT, metadata("1.0-SNAPSHOT"));
        hosted.put(PATH, metadata("1.0-SNAPSHOT"));
        MatcherAssert.assertThat(
            "snapshot metadata is served right after its upload",
            get(group, SNAPSHOT).status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "artifact metadata is served right after its upload",
            get(group, PATH).status(), new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void publishIsServedThroughNestedGroups() throws Exception {
        final Hosted hosted = new Hosted("hosted");
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice inner = group(
            "inner", Map.of("hosted", new Counting(calls, hosted)), "hosted"
        );
        final MavenGroupSlice outer = group(
            "outer",
            Map.of(
                "inner", new TrimPathSlice(inner, "inner"),
                "other", new Fixed(RsStatus.NOT_FOUND)
            ),
            "inner", "other"
        );
        MatcherAssert.assertThat(
            "missing through the outer group",
            get(outer, PATH).status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        get(outer, PATH);
        get(inner, PATH);
        MatcherAssert.assertThat(
            "outer and inner group answer the repeats from memory",
            calls.get(), new IsEqual<>(1)
        );
        hosted.put(PATH, metadata("1.0"));
        MatcherAssert.assertThat(
            "served through the outer group right after the upload",
            get(outer, PATH).status(), new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void proxyRefreshDropsTheRememberedMiss() throws Exception {
        final Switch member = new Switch();
        final MavenGroupSlice group = group("refresh", Map.of("proxy", member), "proxy");
        MatcherAssert.assertThat(
            "missing first", get(group, PATH).status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        member.serve(metadata("2.0"));
        MatcherAssert.assertThat(
            "still answered from memory", get(group, PATH).status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        // What the maven proxy's refreshed-content hook does when new
        // metadata lands in its cache.
        FilteredMetadataCacheRegistry.instance()
            .invalidateAfterProxyRefresh("maven-proxy", "com.acme.widget");
        MatcherAssert.assertThat(
            "served after the refresh event", get(group, PATH).status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void uploadOnAnotherNodeDropsTheRememberedMiss() throws Exception {
        final Switch member = new Switch();
        final MavenGroupSlice group = group("peer", Map.of("hosted", member), "hosted");
        get(group, PATH);
        member.serve(metadata("3.0"));
        // What CacheInvalidationPubSub delivers on this node when another
        // node published the package change (channel cooldown-package).
        FilteredMetadataCacheRegistry.instance().packageReceiver().invalidate("com.acme.widget");
        MatcherAssert.assertThat(
            get(group, PATH).status(), new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void metadataUploadIsPublishedToOtherNodes() throws Exception {
        final List<String> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        FilteredMetadataCacheRegistry.instance().setPackagePublisher(published::add, () -> { });
        try {
            new Hosted("hosted").put(SNAPSHOT, metadata("1.0-SNAPSHOT"));
        } finally {
            FilteredMetadataCacheRegistry.instance().setPackagePublisher(pkg -> { }, () -> { });
        }
        MatcherAssert.assertThat(
            published, new IsEqual<>(List.of("com.acme.widget"))
        );
    }

    @Test
    void pathWithoutPackageIsNeverRemembered() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "no-package",
            Map.of("a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND))),
            "a"
        );
        get(group, "/maven-metadata.xml");
        get(group, "/maven-metadata.xml");
        MatcherAssert.assertThat(calls.get(), new IsEqual<>(2));
    }

    @Test
    void walkWithFailedMemberIsNotRemembered() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "failed",
            Map.of(
                "a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND)),
                "b", new Fixed(RsStatus.INTERNAL_ERROR)
            ),
            "a", "b"
        );
        MatcherAssert.assertThat(
            "a failed member is not an absence",
            get(group, PATH).status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "members are asked again", calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void walkWithThrowingMemberIsNotRemembered() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "threw",
            Map.of(
                "a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND)),
                "b", (line, headers, body) ->
                    CompletableFuture.failedFuture(new java.io.IOException("reset"))
            ),
            "a", "b"
        );
        MatcherAssert.assertThat(
            "a member exception is not an absence",
            get(group, PATH).status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "members are asked again", calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void walkWithThrottledMemberIsNotRemembered() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "throttled",
            Map.of(
                "a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND)),
                "b", new Fixed(RsStatus.TOO_MANY_REQUESTS)
            ),
            "a", "b"
        );
        MatcherAssert.assertThat(
            "a throttled member is not an absence",
            get(group, PATH).status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "members are asked again", calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void walkWithUnverifiedNotFoundIsNotRemembered() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "unverified",
            Map.of(
                "a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND)),
                "b", (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.notFound()
                        .header(NegativeCache.SKIP_HEADER, "true").build()
                )
            ),
            "a", "b"
        );
        final Response response = get(group, PATH);
        MatcherAssert.assertThat(
            "answer is a 404", response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the 404 stays marked for an outer group",
            response.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(false)
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "members are asked again", calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void walkWithClientErrorIsNotRemembered() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "client-error",
            Map.of(
                "a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND)),
                "b", new Fixed(RsStatus.UNAUTHORIZED)
            ),
            "a", "b"
        );
        final Response response = get(group, PATH);
        MatcherAssert.assertThat(
            "answer is a 404", response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the 404 is marked unverified",
            response.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(false)
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "members are asked again", calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void unavailableAnswerIsExplainedByTheRefusalNotByAnotherMember() throws Exception {
        final MavenGroupSlice group = group(
            "refused-reason",
            Map.of(
                "a", (line, headers, body) -> CompletableFuture.completedFuture(
                    new com.auto1.pantera.http.log.AnswerReason(
                        ResponseBuilder.notFound().build()
                    ).stamped("upstream_status_401")
                ),
                "open", (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                        .header(UpstreamCircuitOpenException.HEADER, "true")
                        .header("Retry-After", "17").build()
                )
            ),
            "a", "open"
        );
        final Response response = get(
            new com.auto1.pantera.http.slice.ReasonSlice(group), PATH
        );
        MatcherAssert.assertThat(
            "a refused member is not an absence",
            response.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "the reason is the open circuit, not the other member's 401",
            new com.auto1.pantera.http.log.AnswerReason(response).token(),
            new IsEqual<>(java.util.Optional.of("circuit_open"))
        );
    }

    @Test
    void refusedMembersAnswerUnavailableNotNotFound() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "refused",
            Map.of(
                "a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND)),
                "open", (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                        .header(UpstreamCircuitOpenException.HEADER, "true")
                        .header("Retry-After", "17").build()
                ),
                "full", (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE)
                        .header(LocalRefusal.BULKHEAD, "true").build()
                )
            ),
            "a", "open", "full"
        );
        final Response response = get(group, PATH);
        MatcherAssert.assertThat(
            "a refused member is not an absence",
            response.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "retry hint of the refusal is kept",
            response.headers().values("Retry-After"), new IsEqual<>(List.of("17"))
        );
        MatcherAssert.assertThat(
            "the refusal marker is kept for an outer group",
            new LocalRefusal(response).present(), new IsEqual<>(true)
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "members are asked again", calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void publishDuringTheWalkIsNotOverwrittenByTheMiss() throws Exception {
        final CompletableFuture<Response> parked = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = group(
            "racing",
            Map.of(
                "slow", new Counting(calls, (line, headers, body) -> parked)
            ),
            "slow"
        );
        final CompletableFuture<Response> walking = group.response(
            new RequestLine(RqMethod.GET, PATH), Headers.EMPTY, Content.EMPTY
        );
        // The upload event arrives while the walk is still parked on the
        // member: the 404 the walk then ends with predates the upload.
        FilteredMetadataCacheRegistry.instance()
            .invalidateAfterUpload("maven", "com.acme.widget");
        parked.complete(ResponseBuilder.notFound().build());
        MatcherAssert.assertThat(
            "the walk itself answers 404",
            walking.get(10, TimeUnit.SECONDS).status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "the outdated miss was not remembered", calls.get(), new IsEqual<>(2)
        );
    }

    private static MavenGroupSlice group(
        final String name, final Map<String, Slice> members, final String... order
    ) {
        final Map<String, Slice> map = new HashMap<>(members);
        final SliceResolver resolver = (Key member, int port, int depth) ->
            map.get(member.string());
        return new MavenGroupSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            ),
            name, List.of(order), resolver, 8080, 0, new GroupMetadataCache(name)
        );
    }

    /**
     * A group of the member {@code a}, which answers 404, and the proxy
     * member {@code p}.
     */
    private static MavenGroupSlice withProxy(
        final String name, final AtomicInteger calls, final Slice proxy
    ) {
        final Map<String, Slice> map = Map.of(
            "a", new Counting(calls, new Fixed(RsStatus.NOT_FOUND)), "p", proxy
        );
        final SliceResolver resolver = (Key member, int port, int depth) ->
            map.get(member.string());
        return new MavenGroupSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            ),
            name, List.of("a", "p"), resolver, 8080, 0, new GroupMetadataCache(name),
            "maven-group", java.util.Set.of("p")
        );
    }

    @Test
    void failedProxyDoesNotFailTheGroupNorIsTheMissRemembered() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final MavenGroupSlice group = withProxy(
            "proxy-failed", calls, new Fixed(RsStatus.BAD_GATEWAY)
        );
        final Response response = get(group, PATH);
        MatcherAssert.assertThat(
            "the other member said not found, so the group does",
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "marked so that an outer group does not remember it",
            response.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(false)
        );
        get(group, PATH);
        MatcherAssert.assertThat(
            "and not remembered here: members are asked again",
            calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void skippedProxyDoesNotFailTheGroupAndIsNamedAsTheReason() throws Exception {
        final MavenGroupSlice group = withProxy(
            "proxy-open", new AtomicInteger(),
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                    .header(UpstreamCircuitOpenException.HEADER, "true")
                    .header("Retry-After", "17").build()
            )
        );
        final Response response = get(
            new com.auto1.pantera.http.slice.ReasonSlice(group), PATH
        );
        MatcherAssert.assertThat(
            "the other member said not found, so the group does",
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the access record says a member was not asked, not not_found",
            new com.auto1.pantera.http.log.AnswerReason(response).token(),
            new IsEqual<>(java.util.Optional.of("circuit_open"))
        );
    }

    @Test
    void proxyWithoutAFreePermitKeepsTheGroupUnavailable() throws Exception {
        final Response response = get(
            withProxy(
                "proxy-busy", new AtomicInteger(),
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE)
                        .header(LocalRefusal.BULKHEAD, "true")
                        .header("Retry-After", "3").build()
                )
            ),
            PATH
        );
        // A full bulkhead clears in a moment: the client is told to retry.
        MatcherAssert.assertThat(
            response.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
    }

    private static Response get(final Slice slice, final String path) throws Exception {
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        response.body().asBytesFuture().get(10, TimeUnit.SECONDS);
        return response;
    }

    private static String metadata(final String version) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><metadata>"
            + "<groupId>com.acme</groupId><artifactId>widget</artifactId>"
            + "<versioning><latest>" + version + "</latest><versions><version>"
            + version + "</version></versions>"
            + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
    }

    /**
     * Member that always answers one status.
     */
    private static final class Fixed implements Slice {
        private final RsStatus status;

        Fixed(final RsStatus status) {
            this.status = status;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            return CompletableFuture.completedFuture(ResponseBuilder.from(this.status).build());
        }
    }

    /**
     * Counts the requests a member receives.
     */
    private static final class Counting implements Slice {
        private final AtomicInteger calls;
        private final Slice origin;

        Counting(final AtomicInteger calls, final Slice origin) {
            this.calls = calls;
            this.origin = origin;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.calls.incrementAndGet();
            return this.origin.response(line, headers, body);
        }
    }

    /**
     * Member that answers 404 until told to serve metadata.
     */
    private static final class Switch implements Slice {
        private volatile String body;

        void serve(final String metadata) {
            this.body = metadata;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content content
        ) {
            final String current = this.body;
            final Response response;
            if (current == null) {
                response = ResponseBuilder.notFound().build();
            } else {
                response = ResponseBuilder.ok()
                    .body(current.getBytes(StandardCharsets.UTF_8)).build();
            }
            return CompletableFuture.completedFuture(response);
        }
    }

    /**
     * Hosted Maven member: the real upload slice over a storage, read back
     * by key. Addressed, as a group addresses its members, with the member
     * name as the first path segment.
     */
    private static final class Hosted implements Slice {
        private final Storage storage;
        private final Slice upload;
        private final Slice member;

        Hosted(final String name) {
            this.storage = new InMemoryStorage();
            this.upload = new UploadSlice(this.storage);
            this.member = new TrimPathSlice(this::read, name);
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            return this.member.response(line, headers, body);
        }

        void put(final String path, final String content) throws Exception {
            final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            final Response response = this.upload.response(
                new RequestLine(RqMethod.PUT, path),
                Headers.from("Content-Length", Integer.toString(bytes.length)),
                new Content.From(bytes)
            ).get(10, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "upload of " + path + " succeeds",
                response.status(), new IsEqual<>(RsStatus.CREATED)
            );
        }

        private CompletableFuture<Response> read(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final Key key = new Key.From(line.uri().getPath().substring(1));
            return this.storage.exists(key).thenCompose(
                exists -> {
                    final CompletableFuture<Response> result;
                    if (exists) {
                        result = this.storage.value(key).thenApply(
                            content -> ResponseBuilder.ok().body(content).build()
                        );
                    } else {
                        result = CompletableFuture.completedFuture(
                            ResponseBuilder.notFound().build()
                        );
                    }
                    return result;
                }
            );
        }
    }
}
