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
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.fault.FaultTranslator;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for group routing defects: per-file negative caching,
 * sibling-pin scoping, index-miss hosted probing, redirect handling and
 * PEP 503 name normalisation in {@link GroupResolver}.
 *
 * @since 2.2.9
 */
final class GroupResolverRoutingTest {

    private static final String GROUP = "grp";

    private static final String HOSTED = "hosted";

    private static final String PROXY = "proxy";

    private static final String MAVEN_DIR = "/com/google/guava/guava/31.1/";

    private static final String GO_MODULE = "/github.com/pkg/errors/@v/";

    @Test
    void missingSecondaryFileDoesNotHideOtherFilesOfTheVersion() {
        final GroupResolver resolver = resolver(
            "maven-group",
            new MutableIndex(List.of()),
            List.of(member(PROXY, true, byPath(p -> !p.endsWith("-nonexistent.jar"))))
        );
        MatcherAssert.assertThat(
            "the missing classifier file is a 404",
            get(resolver, MAVEN_DIR + "guava-31.1-nonexistent.jar"),
            new IsEqual<>(404)
        );
        MatcherAssert.assertThat(
            "the main jar of the same version is still served",
            get(resolver, MAVEN_DIR + "guava-31.1.jar"),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the pom of the same version is still served",
            get(resolver, MAVEN_DIR + "guava-31.1.pom"),
            new IsEqual<>(200)
        );
    }

    @Test
    void upstreamHitDoesNotPinLocalVersionsOfTheSameModule() {
        final GroupResolver resolver = resolver(
            "go-group",
            new MutableIndex(List.of(HOSTED)),
            List.of(
                member(HOSTED, false, byPath(p -> p.contains("v0.9.21-qa"))),
                member(PROXY, true, byPath(p -> p.contains("v0.9.1.")))
            )
        );
        MatcherAssert.assertThat(
            "the upstream version is served by the proxy",
            get(resolver, GO_MODULE + "v0.9.1.info"),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the local .mod is still served by the hosted member",
            get(resolver, GO_MODULE + "v0.9.21-qa.mod"),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the local .zip is still served by the hosted member",
            get(resolver, GO_MODULE + "v0.9.21-qa.zip"),
            new IsEqual<>(200)
        );
    }

    @Test
    void pinnedMember404FallsBackToTheFullDeclaredOrderResolution() {
        final GroupResolver resolver = resolver(
            "maven-group",
            new MutableIndex(List.of()),
            List.of(
                member(HOSTED, false, byPath(p -> p.endsWith("guava-31.1-extra.jar"))),
                member(PROXY, true, byPath(p -> p.endsWith("guava-31.1.jar")))
            )
        );
        MatcherAssert.assertThat(
            "the jar is served by the proxy (and pins the version to it)",
            get(resolver, MAVEN_DIR + "guava-31.1.jar"),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "a sibling only the hosted member has is still found",
            get(resolver, MAVEN_DIR + "guava-31.1-extra.jar"),
            new IsEqual<>(200)
        );
    }

    @Test
    void metadataRequestsAreNeverPinnedToAMember() {
        final MutableIndex index = new MutableIndex(List.of(PROXY));
        final AtomicReference<String> hostedBody = new AtomicReference<>("");
        final GroupResolver resolver = resolver(
            "pypi-group",
            index,
            List.of(
                member(HOSTED, false, (line, headers, body) -> {
                    if (hostedBody.get().isEmpty()) {
                        return notFound();
                    }
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok().textBody(hostedBody.get()).build()
                    );
                }),
                member(PROXY, true, (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().textBody("proxy").build()
                ))
            )
        );
        MatcherAssert.assertThat(
            "before the hosted upload the proxy page is served",
            body(resolver, "/simple/six/"),
            new IsEqual<>("proxy")
        );
        hostedBody.set("hosted");
        index.set(List.of(HOSTED, PROXY));
        MatcherAssert.assertThat(
            "after the hosted upload the declared-first hosted member wins",
            body(resolver, "/simple/six/"),
            new IsEqual<>("hosted")
        );
    }

    @Test
    void indexMissProbesHostedMembersBeforeProxies() {
        final AtomicInteger proxyCalls = new AtomicInteger();
        final GroupResolver resolver = resolver(
            "file-group",
            new MutableIndex(List.of()),
            List.of(
                member(HOSTED, false, byPath(p -> true)),
                member(PROXY, true, counting(proxyCalls, RsStatus.NOT_FOUND))
            )
        );
        MatcherAssert.assertThat(
            "a freshly uploaded, not yet indexed hosted file is served",
            get(resolver, "/qa/fresh.txt"),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the proxy is not consulted when a hosted member has the file",
            proxyCalls.get(),
            new IsEqual<>(0)
        );
    }

    @Test
    void indexMissWithHostedOnlyGroupStillServesUnindexedUpload() {
        final GroupResolver resolver = resolver(
            "maven-group",
            new MutableIndex(List.of()),
            List.of(member(HOSTED, false, byPath(p -> true)))
        );
        MatcherAssert.assertThat(
            get(resolver, MAVEN_DIR + "guava-31.1.jar"),
            new IsEqual<>(200)
        );
    }

    @Test
    void toctouFallthroughProbesHostedMembersTheIndexDidNotName() {
        final GroupResolver resolver = resolver(
            "maven-group",
            new MutableIndex(List.of(PROXY)),
            List.of(
                member(HOSTED, false, byPath(p -> p.endsWith("guava-31.2.jar"))),
                member(PROXY, true, byPath(p -> p.endsWith("guava-31.1.jar")))
            )
        );
        MatcherAssert.assertThat(
            get(resolver, "/com/google/guava/guava/31.2/guava-31.2.jar"),
            new IsEqual<>(200)
        );
    }

    @Test
    void memberRedirectIsNeitherA500NorAMemberFailure() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(
            new AutoBlockSettings(0.5, 1, 30, Duration.ofSeconds(60), Duration.ofMinutes(5))
        );
        final Slice redirect = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.movedPermanently().header("Location", "/elsewhere").build()
        );
        final GroupResolver resolver = new GroupResolver(
            GROUP,
            List.of(new MemberSlice(HOSTED, redirect, registry, false)),
            Collections.emptyList(),
            Optional.of(new MutableIndex(List.of(HOSTED))),
            "maven-group",
            Set.of(),
            negativeCache(),
            ForkJoinPool.commonPool()
        );
        final Response resp = resolver.response(
            new RequestLine("GET", MAVEN_DIR + "guava-31.1.jar"), Headers.EMPTY, Content.EMPTY
        ).join();
        resp.body().asBytesFuture().join();
        MatcherAssert.assertThat(
            "a member redirect must not be turned into a 500",
            resp.status().code(),
            new IsNot<>(new IsEqual<>(500))
        );
        MatcherAssert.assertThat(
            "a member redirect must not be synthesised into a storage fault",
            resp.headers().values(FaultTranslator.HEADER_FAULT).isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a member redirect must not count against the member's breaker",
            registry.isBlocked(HOSTED),
            new IsEqual<>(false)
        );
    }

    @Test
    void redirectedTargetedReadIsNotNegativeCached() {
        final NegativeCache cache = negativeCache();
        final GroupResolver resolver = new GroupResolver(
            GROUP,
            List.of(
                member(HOSTED, false, (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.movedPermanently().header("Location", "/x").build()
                )),
                member(PROXY, true, byPath(p -> false))
            ),
            Collections.emptyList(),
            Optional.of(new MutableIndex(List.of(HOSTED))),
            "maven-group",
            Set.of(PROXY),
            cache,
            ForkJoinPool.commonPool()
        );
        MatcherAssert.assertThat(
            "nobody serves the file",
            get(resolver, MAVEN_DIR + "guava-31.1.jar"),
            new IsEqual<>(404)
        );
        MatcherAssert.assertThat(
            "a 404 reached after a member redirect is not an authoritative absence",
            cache.isKnown404(
                new com.auto1.pantera.http.cache.NegativeCacheKey(
                    GROUP, "maven-group", "com.google.guava.guava", "31.1/guava-31.1.jar"
                )
            ),
            new IsEqual<>(false)
        );
    }

    @Test
    void pypiSimpleNameIsNormalisedBeforeTheMemberWalk() {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final Slice hosted = (line, headers, body) -> {
            seen.add(line.uri().getPath());
            if (line.uri().getPath().endsWith("/simple/qa-python-foo-bar/")) {
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.movedPermanently()
                    .header("Location", "/hosted/simple/qa-python-foo-bar/").build()
            );
        };
        final GroupResolver resolver = resolver(
            "pypi-group",
            new MutableIndex(List.of(HOSTED)),
            List.of(member(HOSTED, false, hosted), member(PROXY, true, byPath(p -> false)))
        );
        MatcherAssert.assertThat(
            "a non-normalised project name is served, not a 500",
            get(resolver, "/simple/QA_Python.Foo_Bar/"),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "members receive the PEP 503 normalised name",
            seen.get(0),
            new IsEqual<>("/hosted/simple/qa-python-foo-bar/")
        );
    }

    private static int get(final GroupResolver resolver, final String path) {
        final Response resp = resolver.response(
            new RequestLine("GET", path), Headers.EMPTY, Content.EMPTY
        ).join();
        resp.body().asBytesFuture().join();
        return resp.status().code();
    }

    private static String body(final GroupResolver resolver, final String path) {
        final Response resp = resolver.response(
            new RequestLine("GET", path), Headers.EMPTY, Content.EMPTY
        ).join();
        return new String(resp.body().asBytesFuture().join(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static GroupResolver resolver(
        final String type, final ArtifactIndex index, final List<MemberSlice> members
    ) {
        return new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.of(index),
            type,
            Set.of(PROXY),
            negativeCache(),
            ForkJoinPool.commonPool()
        );
    }

    private static MemberSlice member(final String name, final boolean proxy, final Slice slice) {
        return new MemberSlice(name, slice, proxy);
    }

    private static Slice byPath(final Predicate<String> found) {
        return (line, headers, body) -> {
            if (found.test(line.uri().getPath())) {
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
            return notFound();
        };
    }

    private static Slice counting(final AtomicInteger calls, final RsStatus status) {
        return (line, headers, body) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.from(status).build());
        };
    }

    private static CompletableFuture<Response> notFound() {
        return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
    }

    private static NegativeCache negativeCache() {
        return new NegativeCache(
            new NegativeCacheConfig(
                Duration.ofMinutes(5),
                10_000,
                false,
                NegativeCacheConfig.DEFAULT_L1_MAX_SIZE,
                NegativeCacheConfig.DEFAULT_L1_TTL,
                NegativeCacheConfig.DEFAULT_L2_MAX_SIZE,
                NegativeCacheConfig.DEFAULT_L2_TTL
            )
        );
    }

    /**
     * Index whose {@code locateByName} answer can be changed mid-test.
     */
    private static final class MutableIndex implements ArtifactIndex {
        private final AtomicReference<List<String>> repos;

        MutableIndex(final List<String> repos) {
            this.repos = new AtomicReference<>(repos);
        }

        void set(final List<String> value) {
            this.repos.set(value);
        }

        @Override
        public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
            return CompletableFuture.completedFuture(Optional.of(this.repos.get()));
        }

        @Override
        public CompletableFuture<Void> index(final ArtifactDocument doc) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> remove(final String rn, final String ap) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SearchResult> search(final String q, final int max, final int off) {
            return CompletableFuture.completedFuture(SearchResult.EMPTY);
        }

        @Override
        public CompletableFuture<List<String>> locate(final String path) {
            return CompletableFuture.completedFuture(this.repos.get());
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
