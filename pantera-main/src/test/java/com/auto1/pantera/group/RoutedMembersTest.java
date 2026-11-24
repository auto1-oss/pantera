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
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactIndex;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A group member that has routing rules is asked only for the paths they
 * match.
 *
 * @since 2.2.14
 */
final class RoutedMembersTest {

    /**
     * Hosted member.
     */
    private static final String LOCAL = "libs-release-local";

    /**
     * Proxy member.
     */
    private static final String CENTRAL = "maven-central";

    /**
     * Path of an internal artifact.
     */
    private static final String INTERNAL = "/com/acme/app/1.0/app-1.0.jar";

    /**
     * Path of a public artifact.
     */
    private static final String PUBLIC = "/org/apache/commons/commons-lang3/3.0/commons-lang3-3.0.jar";

    @Test
    void memberWithRulesIsNotAskedForAPathNoRuleMatches() {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final Slice central = RoutedMembersTest.members(
            asked, new RoutingRule.PathPattern(RoutedMembersTest.CENTRAL, "(?!com/acme/).*")
        ).slice(new Key.From(RoutedMembersTest.CENTRAL), 8080, 0);
        final Response internal = central.response(
            new RequestLine("GET", '/' + RoutedMembersTest.CENTRAL + RoutedMembersTest.INTERNAL),
            Headers.EMPTY, Content.EMPTY
        ).join();
        final Response open = central.response(
            new RequestLine("GET", '/' + RoutedMembersTest.CENTRAL + RoutedMembersTest.PUBLIC),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the excluded path is answered as absent",
            internal.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the other path is served by the member",
            open.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "and only that one reached the member",
            asked,
            new IsEqual<>(List.of('/' + RoutedMembersTest.CENTRAL + RoutedMembersTest.PUBLIC))
        );
    }

    @Test
    void prefixRuleLimitsAMemberToItsPrefix() {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final Slice central = RoutedMembersTest.members(
            asked, new RoutingRule.PathPrefix(RoutedMembersTest.CENTRAL, "/org/apache/")
        ).slice(new Key.From(RoutedMembersTest.CENTRAL), 8080, 0);
        central.response(
            new RequestLine("GET", '/' + RoutedMembersTest.CENTRAL + RoutedMembersTest.INTERNAL),
            Headers.EMPTY, Content.EMPTY
        ).join();
        central.response(
            new RequestLine("GET", '/' + RoutedMembersTest.CENTRAL + RoutedMembersTest.PUBLIC),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            asked,
            new IsEqual<>(List.of('/' + RoutedMembersTest.CENTRAL + RoutedMembersTest.PUBLIC))
        );
    }

    @Test
    void memberWithoutRulesIsAskedForEverything() {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final Slice local = RoutedMembersTest.members(
            asked, new RoutingRule.PathPrefix(RoutedMembersTest.CENTRAL, "org/apache/")
        ).slice(new Key.From(RoutedMembersTest.LOCAL), 8080, 0);
        local.response(
            new RequestLine("GET", '/' + RoutedMembersTest.LOCAL + RoutedMembersTest.INTERNAL),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            asked,
            new IsEqual<>(List.of('/' + RoutedMembersTest.LOCAL + RoutedMembersTest.INTERNAL))
        );
    }

    @Test
    void groupDoesNotSendAnExcludedPathToItsProxy() {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final SliceResolver members = RoutedMembersTest.members(
            asked, new RoutingRule.PathPattern(RoutedMembersTest.CENTRAL, "(?!com/acme/).*")
        );
        final NegativeCache cache = new NegativeCache(
            new NegativeCacheConfig(
                Duration.ofMinutes(5), 10_000, false,
                NegativeCacheConfig.DEFAULT_L1_MAX_SIZE, NegativeCacheConfig.DEFAULT_L1_TTL,
                NegativeCacheConfig.DEFAULT_L2_MAX_SIZE, NegativeCacheConfig.DEFAULT_L2_TTL
            )
        );
        final GroupResolver group = new GroupResolver(
            "libs-release",
            List.of(
                new MemberSlice(
                    RoutedMembersTest.LOCAL,
                    (line, headers, body) -> CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    ),
                    false
                ),
                new MemberSlice(
                    RoutedMembersTest.CENTRAL,
                    members.slice(new Key.From(RoutedMembersTest.CENTRAL), 8080, 0), true
                )
            ),
            Collections.emptyList(),
            Optional.<ArtifactIndex>empty(),
            "maven-group",
            Set.of(RoutedMembersTest.CENTRAL),
            cache,
            ForkJoinPool.commonPool()
        );
        final Response resp = group.response(
            new RequestLine("GET", RoutedMembersTest.INTERNAL), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the internal artifact is in no member",
            resp.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "and its name never reached the proxy",
            asked, new IsEqual<>(List.of())
        );
    }

    /**
     * Members that record the path they are asked for and answer 200.
     * @param asked Paths the members were asked for
     * @param rules Routing rules of the group
     * @return Resolver of the members
     */
    private static SliceResolver members(final List<String> asked, final RoutingRule... rules) {
        final Slice recording = (line, headers, body) -> {
            asked.add(line.uri().getPath());
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        final Map<String, Slice> all = Map.of(
            RoutedMembersTest.LOCAL, recording, RoutedMembersTest.CENTRAL, recording
        );
        return new RoutedMembers(
            (name, port, depth) -> all.get(name.string()), List.of(rules)
        );
    }
}
