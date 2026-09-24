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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.http.DockerSlice;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DockerGroupSlice}.
 *
 * @since 2.2.9
 */
final class DockerGroupSliceTest {

    /**
     * R31: the group catalog is the union of every member's catalog, named
     * under the group ({@code docker_group/...}) — the names a client pulls
     * through the group — and includes the proxy member's cached images.
     */
    @Test
    void mergesCatalogsOfAllMembersUnderTheGroupName() {
        final AtomicInteger delegated = new AtomicInteger();
        final List<String> asked = new CopyOnWriteArrayList<>();
        final DockerGroupSlice slice = new DockerGroupSlice(
            counting(delegated),
            "docker_group",
            List.of(
                new MemberSlice(
                    "docker_proxy",
                    catalog(asked, "docker_proxy/library/alpine", "docker_proxy/ayd/test"),
                    true
                ),
                new MemberSlice(
                    "docker_local",
                    catalog(asked, "docker_local/ayd/test", "docker_local/team/nested/img"),
                    false
                )
            )
        );
        final Response resp = slice.response(
            new RequestLine("GET", "/_catalog"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the catalog is the de-duplicated, sorted union under the group name",
            body(resp),
            new IsEqual<>(
                "{\"repositories\":[\"docker_group/ayd/test\",\"docker_group/library/alpine\","
                    + "\"docker_group/team/nested/img\"]}"
            )
        );
        MatcherAssert.assertThat(
            "every member is asked for its own catalog",
            asked.stream().sorted().toList(),
            new IsEqual<>(List.of("/docker_local/_catalog", "/docker_proxy/_catalog"))
        );
        MatcherAssert.assertThat(
            "the catalog does not go through the first-wins walk",
            delegated.get(),
            new IsEqual<>(0)
        );
    }

    /**
     * A full group catalog page links to the next group page, and the
     * group's cursor is handed to each member under the member's own name.
     */
    @Test
    void pagesTheMergedCatalog() {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final DockerGroupSlice slice = new DockerGroupSlice(
            counting(new AtomicInteger()),
            "docker_group",
            List.of(
                new MemberSlice("docker_local", catalog(asked, "docker_local/b", "docker_local/d"), false),
                new MemberSlice("docker_proxy", catalog(asked, "docker_proxy/c"), true)
            )
        );
        final Response resp = slice.response(
            new RequestLine("GET", "/_catalog?n=2&last=docker_group%2Fa"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the page holds the first n names after the cursor",
            body(resp),
            new IsEqual<>("{\"repositories\":[\"docker_group/b\",\"docker_group/c\"]}")
        );
        MatcherAssert.assertThat(
            "a full page links to the next group page",
            resp.headers().values("Link"),
            new IsEqual<>(
                List.of("</v2/docker_group/_catalog?n=2&last=docker_group%2Fc>; rel=\"next\"")
            )
        );
        MatcherAssert.assertThat(
            "members get the cursor under their own name",
            asked.stream().sorted().toList(),
            new IsEqual<>(
                List.of(
                    "/docker_local/_catalog?n=2&last=docker_local%2Fa",
                    "/docker_proxy/_catalog?n=2&last=docker_proxy%2Fa"
                )
            )
        );
    }

    /**
     * R32 for the group: a cursor outside the group's names, or a malformed
     * page size, is a 400 client error.
     */
    @Test
    void rejectsMalformedPagination() {
        final DockerGroupSlice slice = new DockerGroupSlice(
            counting(new AtomicInteger()),
            "docker_group",
            List.of(new MemberSlice("docker_local", catalog(new CopyOnWriteArrayList<>()), false))
        );
        final Response foreign = slice.response(
            new RequestLine("GET", "/_catalog?last=docker_local%2Fx"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a foreign cursor is 400",
            foreign.status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "a foreign cursor is NAME_INVALID",
            body(foreign),
            new StringContains("NAME_INVALID")
        );
        final Response size = slice.response(
            new RequestLine("GET", "/_catalog?n=abc"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a malformed page size is 400",
            size.status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "a malformed page size is PAGINATION_NUMBER_INVALID",
            body(size),
            new StringContains("PAGINATION_NUMBER_INVALID")
        );
    }

    /**
     * A member that fails leaves a partial union; its failure is recorded.
     */
    @Test
    void failingMemberLeavesAPartialCatalog() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(
            new AutoBlockSettings(1.0, 2, 30, Duration.ofSeconds(60), Duration.ofMinutes(5))
        );
        final DockerGroupSlice slice = new DockerGroupSlice(
            counting(new AtomicInteger()),
            "docker_group",
            List.of(
                new MemberSlice("docker_proxy", status(RsStatus.INTERNAL_ERROR), registry, true),
                new MemberSlice(
                    "docker_local", catalog(new CopyOnWriteArrayList<>(), "docker_local/a"),
                    registry, false
                )
            )
        );
        MatcherAssert.assertThat(
            "the answering member's names are served",
            body(
                slice.response(
                    new RequestLine("GET", "/_catalog"), Headers.EMPTY, Content.EMPTY
                ).join()
            ),
            new IsEqual<>("{\"repositories\":[\"docker_group/a\"]}")
        );
        registry.recordFailure("docker_proxy");
        MatcherAssert.assertThat(
            "the failure was recorded on the member's breaker",
            registry.isBlocked("docker_proxy"),
            new IsEqual<>(true)
        );
    }

    /**
     * When every member is unavailable the group answers 503 + Retry-After,
     * never an empty catalog.
     */
    @Test
    void allMembersUnavailableIsServiceUnavailable() {
        final Slice marked = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                .header(UpstreamCircuitOpenException.HEADER, "true")
                .header("Retry-After", "30")
                .build()
        );
        final Response resp = new DockerGroupSlice(
            counting(new AtomicInteger()),
            "docker_group",
            List.of(new MemberSlice("docker_proxy", marked, true))
        ).response(new RequestLine("GET", "/_catalog"), Headers.EMPTY, Content.EMPTY).join();
        body(resp);
        MatcherAssert.assertThat(
            "the status is 503",
            resp.status(),
            new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "Retry-After carries the member's hint",
            resp.headers().values("Retry-After"),
            new IsEqual<>(List.of("30"))
        );
    }

    /**
     * R29 for the group: a member's tags page links to the next page under
     * the group's own path, so a client following Link stays on the group.
     */
    @Test
    void rewritesTheTagsLinkToTheGroupPath() {
        final Slice walk = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Link", "</v2/docker_local/ayd/test/tags/list?n=1&last=2.0.0>; rel=\"next\"")
                .jsonBody("{\"name\":\"ayd/test\",\"tags\":[\"2.0.0\"]}")
                .build()
        );
        final Response resp = new DockerGroupSlice(
            walk,
            "docker_group",
            List.of(new MemberSlice("docker_local", status(RsStatus.NOT_FOUND), false))
        ).response(
            new RequestLine("GET", "/ayd/test/tags/list?n=1"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the Link names the group",
            resp.headers().values("Link"),
            new IsEqual<>(
                List.of("</v2/docker_group/ayd/test/tags/list?n=1&last=2.0.0>; rel=\"next\"")
            )
        );
        MatcherAssert.assertThat(
            "the body is relayed",
            body(resp),
            new IsEqual<>("{\"name\":\"ayd/test\",\"tags\":[\"2.0.0\"]}")
        );
    }

    /**
     * T05: following the group's tags Link for an image only the proxy
     * member holds keeps returning the proxy's pages. The hosted member,
     * walked first, must answer 404 NAME_UNKNOWN for the cursor page, not
     * an empty 200 that wins the walk.
     */
    @Test
    void pagesProxyTagsPastAHostedMemberThatLacksTheImage() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("repositories/team/img/_manifests/tags/1/current/link"),
            new Content.From("sha256:abc".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Slice hosted = new DockerSlice(new AstoDocker("docker_local", storage));
        final Slice local = (line, headers, body) -> hosted.response(
            new RequestLine(
                line.method().value(),
                "/v2" + line.uri().toString().substring("/docker_local".length())
            ),
            headers, body
        );
        final Slice proxy = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header(
                    "Link",
                    "</v2/docker_proxy/library/alpine/tags/list?n=2&last=20190408>; rel=\"next\""
                )
                .jsonBody("{\"name\":\"library/alpine\",\"tags\":[\"20190228\",\"20190408\"]}")
                .build()
        );
        final List<MemberSlice> members = List.of(
            new MemberSlice("docker_local", local, false),
            new MemberSlice("docker_proxy", proxy, true)
        );
        final Response resp = new DockerGroupSlice(
            new GroupResolver(
                "docker_group", members, List.of(), Optional.empty(), "docker-group",
                Set.of("docker_proxy"),
                new NegativeCache(
                    new NegativeCacheConfig(
                        Duration.ofMinutes(5), 10_000, false,
                        NegativeCacheConfig.DEFAULT_L1_MAX_SIZE,
                        NegativeCacheConfig.DEFAULT_L1_TTL,
                        NegativeCacheConfig.DEFAULT_L2_MAX_SIZE,
                        NegativeCacheConfig.DEFAULT_L2_TTL
                    )
                ),
                ForkJoinPool.commonPool()
            ),
            "docker_group",
            members
        ).response(
            new RequestLine("GET", "/library/alpine/tags/list?n=2&last=2.7"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the proxy page is served",
            body(resp),
            new IsEqual<>("{\"name\":\"library/alpine\",\"tags\":[\"20190228\",\"20190408\"]}")
        );
        MatcherAssert.assertThat(
            "the next Link stays on the group",
            resp.headers().values("Link"),
            new IsEqual<>(
                List.of(
                    "</v2/docker_group/library/alpine/tags/list?n=2&last=20190408>; rel=\"next\""
                )
            )
        );
    }

    @Test
    void otherPathsGoToTheWalk() {
        final AtomicInteger delegated = new AtomicInteger();
        body(
            new DockerGroupSlice(
                counting(delegated),
                "docker_group",
                List.of(new MemberSlice("docker_local", status(RsStatus.OK), false))
            ).response(
                new RequestLine("GET", "/ayd/test/manifests/latest"), Headers.EMPTY, Content.EMPTY
            ).join()
        );
        MatcherAssert.assertThat(delegated.get(), new IsEqual<>(1));
    }

    private static Slice catalog(final List<String> asked, final String... names) {
        return (line, headers, body) -> {
            asked.add(line.uri().toString());
            final StringBuilder json = new StringBuilder("{\"repositories\":[");
            for (int idx = 0; idx < names.length; idx += 1) {
                if (idx > 0) {
                    json.append(',');
                }
                json.append('"').append(names[idx]).append('"');
            }
            json.append("]}");
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().jsonBody(json.toString()).build()
            );
        };
    }

    private static Slice status(final RsStatus status) {
        return (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.from(status).build()
        );
    }

    private static Slice counting(final AtomicInteger calls) {
        return (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        };
    }

    private static String body(final Response resp) {
        return new String(resp.body().asBytesFuture().join(), StandardCharsets.UTF_8);
    }
}
