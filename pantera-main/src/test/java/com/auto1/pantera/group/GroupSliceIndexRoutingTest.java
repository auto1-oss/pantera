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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that GroupSlice routes group requests through {@code locateByName()}
 * for ALL supported adapter types — never through the legacy {@code locate()} path.
 *
 * <p>Four invariants are tested:
 * <ol>
 *   <li>Artifact URLs for every known adapter → {@code locateByName()} called, {@code locate()} never called</li>
 *   <li>Index hit (index returns a repo) → only the matched member receives the request</li>
 *   <li>Index miss (index returns empty) → all members receive the request (fanout)</li>
 *   <li>Metadata/unparseable URLs → direct fanout with NO index call at all</li>
 * </ol>
 *
 * @since 1.21.0
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
final class GroupSliceIndexRoutingTest {

    // ---- locateByName() called (not locate()) for all adapter types ----

    @ParameterizedTest
    @CsvSource({
        "maven-group,  /com/google/guava/guava/31.1/guava-31.1.jar,          com.google.guava.guava",
        "npm-group,    /lodash/-/lodash-4.17.21.tgz,                         lodash",
        "docker-group, /v2/library/nginx/manifests/latest,                   library/nginx",
        "pypi-group,   /simple/numpy/,                                       numpy",
        "go-group,     /github.com/gin-gonic/gin/@v/v1.9.1.info,             github.com/gin-gonic/gin",
        "gem-group,    /gems/rails-7.1.2.gem,                                rails",
        "php-group,    /p2/monolog/monolog.json,                             monolog/monolog",
        "helm-group,   /charts/nginx-1.2.3.tgz,                             nginx",
        "debian-group, /pool/main/n/nginx/nginx_1.18.0_amd64.deb,           nginx_amd64",
        "hex-group,    /api/packages/phoenix,                                phoenix",
        "file-group,   /reports/2024/q1.pdf,                                 reports.2024.q1.pdf",
    })
    void locateByNameCalledNotLocate(final String repoType, final String url,
        final String expectedName) {
        final RecordingIndex idx = new RecordingIndex(List.of());
        final GroupSlice slice = sliceWithIndex(repoType, idx, List.of("member-a", "member-b"));
        slice.response(new RequestLine("GET", url), Headers.EMPTY, Content.EMPTY).join();
        assertEquals(
            List.of(expectedName.strip()),
            idx.locateByNameCalls,
            "locateByName() must be called with parsed name for: " + url
        );
        assertTrue(idx.locateCalls.isEmpty(),
            "locate() must NOT be called for: " + url);
    }

    // ---- Index hit → only the matching member receives the request ----

    @Test
    void indexHitQueriesOnlyMatchingMember() {
        final String target = "maven-proxy";
        final RecordingIndex idx = new RecordingIndex(List.of(target));
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final AtomicInteger localCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(target, countingSlice(proxyCount));
        slices.put("maven-local", countingSlice(localCount));
        final GroupSlice slice = new GroupSlice(
            new MapResolver(slices),
            "maven-group",
            List.of("maven-local", target),
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            Set.of(target),
            "maven-group"
        );
        slice.response(
            new RequestLine("GET", "/com/google/guava/guava/31.1/guava-31.1.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(1, proxyCount.get(),
            "Only the matched member should receive the request");
        assertEquals(0, localCount.get(),
            "Unmatched member must NOT receive any request");
        assertTrue(idx.locateByNameCalls.contains("com.google.guava.guava"),
            "locateByName() called with parsed name");
        assertTrue(idx.locateCalls.isEmpty(), "locate() must never be called");
    }

    // ---- Nested group: index hit on leaf resolves to the correct direct member ----

    @Test
    void nestedGroupIndexHitResolvesLeafToDirectMember() {
        // libs-release topology:
        //   libs-release-local  (leaf)
        //   remote-repos        (nested group) → contains jboss, maven-central
        // Index says: com.google.guava.guava is in jboss.
        // leafToMember: jboss → remote-repos, maven-central → remote-repos
        // Expected: only remote-repos is queried (not libs-release-local).
        final String jboss = "jboss";
        final RecordingIndex idx = new RecordingIndex(List.of(jboss));
        final AtomicInteger remoteReposCount = new AtomicInteger(0);
        final AtomicInteger localCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put("remote-repos", countingSlice(remoteReposCount));
        slices.put("libs-release-local", countingSlice(localCount));
        final Map<String, String> leafToMember = new HashMap<>();
        leafToMember.put(jboss, "remote-repos");
        leafToMember.put("maven-central", "remote-repos");
        leafToMember.put("libs-release-local", "libs-release-local");
        final GroupSlice slice = new GroupSlice(
            new MapResolver(slices),
            "libs-release",
            List.of("libs-release-local", "remote-repos"),
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            Set.of("remote-repos"),
            "maven-group",
            leafToMember
        );
        slice.response(
            new RequestLine("GET",
                "/com/google/guava/guava/19.0.0.jbossorg-1/guava-19.0.0.jbossorg-1.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(1, remoteReposCount.get(),
            "remote-repos (containing jboss) must be queried on index hit");
        assertEquals(0, localCount.get(),
            "libs-release-local must NOT be queried when index routes to remote-repos");
        assertTrue(idx.locateByNameCalls.contains("com.google.guava.guava"),
            "locateByName() called with parsed Maven name");
        assertTrue(idx.locateCalls.isEmpty(), "locate() must never be called");
    }

    // ---- Index hit for each new adapter type ----

    @ParameterizedTest
    @CsvSource({
        "helm-group,   /charts/nginx-1.2.3.tgz,                       nginx,           helm-proxy",
        "debian-group, /pool/main/n/nginx/nginx_1.18.0_amd64.deb,     nginx_amd64,     debian-proxy",
        "hex-group,    /api/packages/phoenix,                          phoenix,         hex-proxy",
        "file-group,   /reports/2024/q1.pdf,                           reports.2024.q1.pdf, file-hosted",
    })
    void newAdapterTypesIndexHitQueriesOnlyMatchingMember(final String repoType,
        final String url, final String expectedName, final String targetMember) {
        final RecordingIndex idx = new RecordingIndex(List.of(targetMember));
        final AtomicInteger targetCount = new AtomicInteger(0);
        final AtomicInteger otherCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(targetMember, countingSlice(targetCount));
        slices.put("other-member", countingSlice(otherCount));
        final GroupSlice slice = new GroupSlice(
            new MapResolver(slices),
            repoType,
            List.of(targetMember, "other-member"),
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            Set.of(targetMember),
            repoType
        );
        slice.response(new RequestLine("GET", url), Headers.EMPTY, Content.EMPTY).join();
        assertEquals(
            List.of(expectedName.strip()),
            idx.locateByNameCalls,
            "locateByName() must be called with parsed name: " + expectedName.strip()
        );
        assertTrue(idx.locateCalls.isEmpty(), "locate() must never be called for " + repoType);
        assertEquals(1, targetCount.get(), "Target member must receive request on index hit");
        assertEquals(0, otherCount.get(), "Other member must NOT receive request on index hit");
    }

    // ---- Index miss → only proxy members queried (not all members) ----

    @Test
    void indexMissQueriesOnlyProxyMembers() {
        final RecordingIndex idx = new RecordingIndex(List.of()); // empty → miss
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put("maven-proxy", countingSlice(proxyCount));
        slices.put("maven-hosted", countingSlice(hostedCount));
        final GroupSlice slice = new GroupSlice(
            new MapResolver(slices),
            "maven-group",
            List.of("maven-hosted", "maven-proxy"),
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            Set.of("maven-proxy"), // only maven-proxy is a proxy member
            "maven-group"
        );
        slice.response(
            new RequestLine("GET", "/com/example/unknown/1.0/unknown-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(1, proxyCount.get(),
            "Proxy member must receive request on index miss");
        assertEquals(0, hostedCount.get(),
            "Hosted member must NOT receive request on index miss (fully indexed)");
        assertFalse(idx.locateByNameCalls.isEmpty(), "locateByName() still called on index miss");
        assertTrue(idx.locateCalls.isEmpty(), "locate() must never be called");
    }

    @Test
    void indexMissWithAllHostedMembersReturns404Immediately() {
        final RecordingIndex idx = new RecordingIndex(List.of()); // empty → miss
        final AtomicInteger hostedACount = new AtomicInteger(0);
        final AtomicInteger hostedBCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put("hosted-a", countingSlice(hostedACount));
        slices.put("hosted-b", countingSlice(hostedBCount));
        final GroupSlice slice = new GroupSlice(
            new MapResolver(slices),
            "maven-group",
            List.of("hosted-a", "hosted-b"),
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            Collections.emptySet(), // no proxy members
            "maven-group"
        );
        final Response resp = slice.response(
            new RequestLine("GET", "/com/example/unknown/1.0/unknown-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(404, resp.status().code(),
            "Must return 404 immediately when no proxy members exist on index miss");
        assertEquals(0, hostedACount.get(),
            "hosted-a must NOT receive any request (all-hosted group, index miss)");
        assertEquals(0, hostedBCount.get(),
            "hosted-b must NOT receive any request (all-hosted group, index miss)");
        assertFalse(idx.locateByNameCalls.isEmpty(), "locateByName() still called on index miss");
        assertTrue(idx.locateCalls.isEmpty(), "locate() must never be called");
    }

    @Test
    void indexMissTriggersFanoutWhenAllMembersAreProxy() {
        final RecordingIndex idx = new RecordingIndex(List.of()); // empty → miss
        final AtomicInteger memberACount = new AtomicInteger(0);
        final AtomicInteger memberBCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put("member-a", countingSlice(memberACount));
        slices.put("member-b", countingSlice(memberBCount));
        final GroupSlice slice = new GroupSlice(
            new MapResolver(slices),
            "maven-group",
            List.of("member-a", "member-b"),
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            Set.of("member-a", "member-b"), // all are proxy members
            "maven-group"
        );
        slice.response(
            new RequestLine("GET", "/com/example/unknown/1.0/unknown-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(1, memberACount.get(),
            "member-a must receive request on index miss (all-proxy group)");
        assertEquals(1, memberBCount.get(),
            "member-b must receive request on index miss (all-proxy group)");
        assertFalse(idx.locateByNameCalls.isEmpty(), "locateByName() still called on index miss");
        assertTrue(idx.locateCalls.isEmpty(), "locate() must never be called");
    }

    // ---- Metadata/unparseable URLs → direct fanout, no index call ----

    @ParameterizedTest
    @CsvSource({
        "helm-group,   /index.yaml",
        "debian-group, /dists/stable/Release",
        "debian-group, /dists/stable/InRelease",
        "debian-group, /dists/stable/main/binary-amd64/Packages",
        "hex-group,    /names",
    })
    void metadataUrlSkipsIndexAndFansOut(final String repoType, final String url) {
        final RecordingIndex idx = new RecordingIndex(List.of("member-a"));
        final AtomicInteger memberACount = new AtomicInteger(0);
        final AtomicInteger memberBCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put("member-a", countingSlice(memberACount));
        slices.put("member-b", countingSlice(memberBCount));
        final GroupSlice slice = new GroupSlice(
            new MapResolver(slices),
            repoType,
            List.of("member-a", "member-b"),
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            Set.of("member-a"),
            repoType
        );
        slice.response(new RequestLine("GET", url), Headers.EMPTY, Content.EMPTY).join();
        assertTrue(idx.locateByNameCalls.isEmpty(),
            "locateByName() must NOT be called for metadata URL: " + url);
        assertTrue(idx.locateCalls.isEmpty(),
            "locate() must NOT be called for metadata URL: " + url);
        assertEquals(1, memberACount.get(),
            "member-a must receive request on direct fanout for: " + url);
        assertEquals(1, memberBCount.get(),
            "member-b must receive request on direct fanout for: " + url);
    }

    // ---- locate() is never called for any known adapter type ----

    @Test
    void locateIsNeverCalledForAnyKnownAdapterType() {
        final String[][] cases = {
            {"maven-group",  "/com/google/guava/guava/31.1/guava-31.1.jar"},
            {"npm-group",    "/lodash/-/lodash-4.17.21.tgz"},
            {"docker-group", "/v2/library/nginx/manifests/latest"},
            {"pypi-group",   "/simple/numpy/"},
            {"go-group",     "/github.com/gin-gonic/gin/@v/v1.9.1.info"},
            {"gem-group",    "/gems/rails-7.1.2.gem"},
            {"php-group",    "/p2/monolog/monolog.json"},
            {"helm-group",   "/charts/nginx-1.2.3.tgz"},
            {"debian-group", "/pool/main/n/nginx/nginx_1.18.0_amd64.deb"},
            {"hex-group",    "/api/packages/phoenix"},
            {"file-group",   "/reports/2024/q1.pdf"},
        };
        for (final String[] tc : cases) {
            final RecordingIndex idx = new RecordingIndex(List.of());
            final GroupSlice slice = sliceWithIndex(tc[0], idx, List.of("member-a", "member-b"));
            slice.response(new RequestLine("GET", tc[1]), Headers.EMPTY, Content.EMPTY).join();
            assertFalse(idx.locateByNameCalls.isEmpty(),
                "locateByName() must be called for " + tc[0] + " " + tc[1]);
            assertTrue(idx.locateCalls.isEmpty(),
                "locate() must NEVER be called for " + tc[0] + " " + tc[1]);
        }
    }

    // ---- Helpers ----

    private static GroupSlice sliceWithIndex(
        final String repoType,
        final ArtifactIndex idx,
        final List<String> members
    ) {
        final Map<String, Slice> slices = new HashMap<>();
        for (final String m : members) {
            slices.put(m, (line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.ok().build()));
        }
        return new GroupSlice(
            new MapResolver(slices),
            repoType,
            members,
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            Set.copyOf(members),
            repoType
        );
    }

    private static Slice countingSlice(final AtomicInteger counter) {
        return (line, headers, body) -> {
            counter.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
    }

    /**
     * ArtifactIndex that records calls to {@code locate()} and {@code locateByName()}.
     * Returns a configurable list of repository names for both methods.
     */
    private static final class RecordingIndex implements ArtifactIndex {
        final List<String> locateByNameCalls = new CopyOnWriteArrayList<>();
        final List<String> locateCalls = new CopyOnWriteArrayList<>();
        private final List<String> repos;

        RecordingIndex(final List<String> repos) {
            this.repos = repos;
        }

        @Override
        public CompletableFuture<Void> index(final ArtifactDocument doc) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> remove(
            final String repoName, final String artifactPath
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SearchResult> search(
            final String query, final int maxResults, final int offset
        ) {
            return CompletableFuture.completedFuture(SearchResult.EMPTY);
        }

        @Override
        public CompletableFuture<List<String>> locate(final String artifactPath) {
            this.locateCalls.add(artifactPath);
            return CompletableFuture.completedFuture(this.repos);
        }

        @Override
        public CompletableFuture<List<String>> locateByName(final String artifactName) {
            this.locateByNameCalls.add(artifactName);
            return CompletableFuture.completedFuture(this.repos);
        }

        @Override
        public void close() {
            // nop
        }
    }

    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> slices;

        MapResolver(final Map<String, Slice> slices) {
            this.slices = slices;
        }

        @Override
        public Slice slice(final Key name, final int port, final int depth) {
            final Slice s = this.slices.get(name.string());
            return s != null ? s
                : (line, headers, body) ->
                    CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
    }
}
