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
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link GroupResolver#querySequentially} — the Nexus-style
 * sequential member traversal added under {@link
 * GroupResolver.MembersStrategy#SEQUENTIAL}.
 *
 * @since 2.2.0
 */
final class SequentialGroupTraversalTest {

    private static final String GROUP = "test-group";
    private static final String REPO_TYPE = "maven";
    private static final String JAR_PATH = "/com/example/x/1.0/x-1.0.jar";

    @Test
    void firstMemberWithArtifactWinsAndLaterMembersAreNotCalled() throws Exception {
        final AtomicInteger memberOneCalls = new AtomicInteger();
        final AtomicInteger memberTwoCalls = new AtomicInteger();
        final byte[] payload = "hit".getBytes();
        final Slice memberOne = (line, headers, body) -> {
            memberOneCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(payload).build()
            );
        };
        final Slice memberTwo = (line, headers, body) -> {
            memberTwoCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        };
        final GroupResolver resolver = buildSequential(
            List.of("one", "two"),
            Map.of("one", memberOne, "two", memberTwo)
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(200, resp.status().code());
        assertEquals(1, memberOneCalls.get());
        assertEquals(0, memberTwoCalls.get(), "second member must not be queried");
    }

    @Test
    void cascadesPastFourOhFourToNextMember() throws Exception {
        final byte[] payload = "found-on-second".getBytes();
        final Slice missing = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        final Slice present = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().body(payload).build());
        final GroupResolver resolver = buildSequential(
            List.of("first", "second"),
            Map.of("first", missing, "second", present)
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(200, resp.status().code());
        assertArrayEquals(payload, resp.body().asBytes());
    }

    @Test
    void returnsNotFoundWhenEveryMemberMisses() throws Exception {
        final Slice miss = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        final GroupResolver resolver = buildSequential(
            List.of("a", "b", "c"),
            Map.of("a", miss, "b", miss, "c", miss)
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(404, resp.status().code());
    }

    @Test
    void skipsOpenCircuitMemberWithoutUpstreamCall() throws Exception {
        // First member's circuit is forced open below; the test asserts
        // its upstream slice is NOT invoked even though it's first in
        // declared order. The second member serves the response.
        final AtomicInteger downstreamCalls = new AtomicInteger();
        final Slice downedMemberSlice = (line, headers, body) -> {
            downstreamCalls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        final byte[] payload = "served".getBytes();
        final Slice live = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().body(payload).build());

        // Build a registry that trips after a single failure: minimumNumberOfCalls=1,
        // failureRateThreshold=0.01 (anything above zero failures -> trip).
        final AutoBlockRegistry tripped = new AutoBlockRegistry(
            new AutoBlockSettings(
                0.01, 1, 30,
                Duration.ofSeconds(20), Duration.ofMinutes(5)
            )
        );
        // Force open by recording a failure; isBlocked("downed") will now be true.
        tripped.recordFailure("downed");

        final List<MemberSlice> members = List.of(
            new MemberSlice("downed", downedMemberSlice, tripped, true),
            new MemberSlice("live", live,
                new AutoBlockRegistry(AutoBlockSettings.defaults()), true)
        );
        final GroupResolver resolver = new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.empty(),
            REPO_TYPE,
            Set.of("downed", "live"),
            buildNegativeCache(),
            ForkJoinPool.commonPool(),
            GroupResolver.MembersStrategy.SEQUENTIAL
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(200, resp.status().code());
        assertArrayEquals(payload, resp.body().asBytes());
        assertEquals(0, downstreamCalls.get(),
            "downed member's slice must not be called when its circuit is open");
    }

    // ===== helpers (mirror GroupResolverTest.buildResolver / buildNegativeCache) =====

    private static GroupResolver buildSequential(
        final List<String> memberNames, final Map<String, Slice> sliceMap
    ) {
        final List<MemberSlice> members = memberNames.stream()
            .map(name -> {
                final Slice s = sliceMap.getOrDefault(name,
                    (line, headers, body) ->
                        CompletableFuture.completedFuture(ResponseBuilder.notFound().build()));
                return new MemberSlice(name, s, true);
            })
            .toList();
        return new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.empty(),
            REPO_TYPE,
            Set.copyOf(memberNames),
            buildNegativeCache(),
            ForkJoinPool.commonPool(),
            GroupResolver.MembersStrategy.SEQUENTIAL
        );
    }

    private static NegativeCache buildNegativeCache() {
        final NegativeCacheConfig config = new NegativeCacheConfig(
            Duration.ofMinutes(5),
            10_000,
            false,
            NegativeCacheConfig.DEFAULT_L1_MAX_SIZE,
            NegativeCacheConfig.DEFAULT_L1_TTL,
            NegativeCacheConfig.DEFAULT_L2_MAX_SIZE,
            NegativeCacheConfig.DEFAULT_L2_TTL
        );
        return new NegativeCache(config);
    }
}
