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
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Cooldown correctness of the Maven group's metadata path: the group's
 * cache of the winning member's filtered bytes follows cooldown package
 * events, and a member's cooldown verdict is relayed, never walked past
 * or replaced by stale bytes.
 *
 * @since 2.2.9
 */
final class MavenGroupSliceCooldownTest {

    /**
     * Artifact-level metadata of the changed package.
     */
    private static final String GUAVA = "/com/google/guava/guava/maven-metadata.xml";

    /**
     * Snapshot-level metadata of the changed package.
     */
    private static final String GUAVA_SNAP =
        "/com/google/guava/guava/1.0-SNAPSHOT/maven-metadata.xml";

    /**
     * Metadata of an unrelated package with a shared prefix.
     */
    private static final String TESTLIB = "/com/google/guava/guava-testlib/maven-metadata.xml";

    @Test
    void packageChangeDropsEveryPrimaryEntryOfThatPackageOnly() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("cd-group-pkg");
        final byte[] data = "<metadata/>".getBytes(StandardCharsets.UTF_8);
        cache.put(GUAVA, data);
        cache.put(GUAVA_SNAP, data);
        cache.put(TESTLIB, data);
        FilteredMetadataCacheRegistry.instance().packageReceiver()
            .invalidate("com.google.guava.guava");
        MatcherAssert.assertThat(
            "artifact-level entry of the changed package dropped",
            cache.get(GUAVA).get(5, TimeUnit.SECONDS).isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "snapshot-level entry of the changed package dropped",
            cache.get(GUAVA_SNAP).get(5, TimeUnit.SECONDS).isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "unrelated package kept",
            cache.get(TESTLIB).get(5, TimeUnit.SECONDS).isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "last-known-good tier kept for the all-members-failed fallback",
            cache.getStaleWithFallback(GUAVA).get(5, TimeUnit.SECONDS).isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void allChangedDropsEveryPrimaryEntry() throws Exception {
        final GroupMetadataCache cache = new GroupMetadataCache("cd-group-all");
        final byte[] data = "<metadata/>".getBytes(StandardCharsets.UTF_8);
        cache.put(GUAVA, data);
        cache.put(TESTLIB, data);
        FilteredMetadataCacheRegistry.instance().packageReceiver().invalidateAll();
        MatcherAssert.assertThat(
            "first entry dropped",
            cache.get(GUAVA).get(5, TimeUnit.SECONDS).isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "second entry dropped",
            cache.get(TESTLIB).get(5, TimeUnit.SECONDS).isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void primaryTtlMatchesTheEnvelopeTtlSoNaturalExpiryShowsUp() throws Exception {
        final Field ttl = GroupMetadataCache.class.getDeclaredField("ttl");
        ttl.setAccessible(true);
        MatcherAssert.assertThat(
            ttl.get(new GroupMetadataCache("cd-group-ttl")),
            new IsEqual<>(Duration.ofMinutes(10))
        );
    }

    @Test
    void unblockIsVisibleThroughTheGroupOnTheNextRequest() throws Exception {
        final AtomicReference<String> version = new AtomicReference<>("1.0");
        final Slice member = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Type", "application/xml")
                .body(
                    ("<metadata><version>" + version.get() + "</version></metadata>")
                        .getBytes(StandardCharsets.UTF_8)
                )
                .build()
        );
        final MavenGroupSlice group = group("cd-group-unblock", Map.of("proxy", member));
        MatcherAssert.assertThat(
            "first view",
            body(group, "/com/ext/unblocked/maven-metadata.xml"),
            new IsEqual<>("<metadata><version>1.0</version></metadata>")
        );
        version.set("2.0");
        FilteredMetadataCacheRegistry.instance().packageReceiver()
            .invalidate("com.ext.unblocked");
        MatcherAssert.assertThat(
            "view after the package changed",
            body(group, "/com/ext/unblocked/maven-metadata.xml"),
            new IsEqual<>("<metadata><version>2.0</version></metadata>")
        );
    }

    @Test
    void memberCooldownVerdictIsRelayedNotWalkedPastNorStale() throws Exception {
        final String path = "/com/ext/allblocked/maven-metadata.xml";
        final GroupMetadataCache cache = new GroupMetadataCache("cd-group-verdict");
        cache.put(path, "<metadata>stale</metadata>".getBytes(StandardCharsets.UTF_8));
        cache.invalidate(path);
        final AtomicInteger second = new AtomicInteger();
        final MavenGroupSlice group = new MavenGroupSlice(
            new NotFoundSlice(), "cd-group-verdict", List.of("blocked", "other"),
            new MapResolver(
                Map.of(
                    "blocked", new VerdictSlice(),
                    "other", (line, headers, body) -> {
                        second.incrementAndGet();
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.ok().body("<metadata/>".getBytes(StandardCharsets.UTF_8))
                                .build()
                        );
                    }
                )
            ),
            8080, 0, cache
        );
        final Response resp = group.response(
            new RequestLine("GET", path), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "status relayed",
            resp.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "marker relayed",
            resp.headers().values("X-Pantera-Cooldown"), new IsEqual<>(List.of("all-blocked"))
        );
        MatcherAssert.assertThat(
            "body relayed",
            new String(resp.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("all versions blocked")
        );
        MatcherAssert.assertThat(
            "later member not consulted",
            second.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "verdict not cached as the group's metadata",
            cache.get(path).get(5, TimeUnit.SECONDS), new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void checksumOfBlockedMetadataRelaysTheVerdict() throws Exception {
        final MavenGroupSlice group = group(
            "cd-group-sha1", Map.of("blocked", new VerdictSlice())
        );
        final Response resp = group.response(
            new RequestLine("GET", "/com/ext/sha/maven-metadata.xml.sha1"),
            Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "status relayed",
            resp.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "marker relayed",
            resp.headers().values("X-Pantera-Cooldown"), new IsEqual<>(List.of("all-blocked"))
        );
    }

    private static MavenGroupSlice group(final String name, final Map<String, Slice> members) {
        return new MavenGroupSlice(
            new NotFoundSlice(), name, List.copyOf(members.keySet()),
            new MapResolver(members), 8080, 0, new GroupMetadataCache(name)
        );
    }

    private static String body(final Slice slice, final String path) throws Exception {
        return new String(
            slice.response(new RequestLine("GET", path), Headers.EMPTY, Content.EMPTY)
                .get(10, TimeUnit.SECONDS).body().asBytes(),
            StandardCharsets.UTF_8
        );
    }

    /**
     * Member answering with the proxy's all-versions-blocked verdict.
     */
    private static final class VerdictSlice implements Slice {
        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.forbidden()
                    .header("X-Pantera-Cooldown", "all-blocked")
                    .textBody("all versions blocked")
                    .build()
            );
        }
    }

    /**
     * Delegate for non-metadata requests.
     */
    private static final class NotFoundSlice implements Slice {
        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
    }

    /**
     * Resolver over a fixed member map.
     */
    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> map;

        MapResolver(final Map<String, Slice> map) {
            this.map = map;
        }

        @Override
        public Slice slice(final Key name, final int port, final int depth) {
            return this.map.get(name.string());
        }
    }
}
