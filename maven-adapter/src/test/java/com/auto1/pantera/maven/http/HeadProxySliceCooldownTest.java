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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HEAD on a Maven proxy must honour cooldown exactly like GET: a cache-miss
 * HEAD of a blocked version answers the cooldown 403, not upstream's 200.
 *
 * @since 2.2.9
 */
final class HeadProxySliceCooldownTest {

    /**
     * Blocked jar.
     */
    private static final String JAR = "/com/example/lib/1.0/lib-1.0.jar";

    /**
     * Cooldown requests seen by the service.
     */
    private List<CooldownRequest> seen;

    /**
     * Storage.
     */
    private InMemoryStorage storage;

    @BeforeEach
    void init() {
        CooldownResponseRegistry.instance()
            .register("maven-proxy", new MavenCooldownResponseFactory());
        this.seen = new CopyOnWriteArrayList<>();
        this.storage = new InMemoryStorage();
    }

    @Test
    void headOfABlockedVersionIsForbiddenLikeGet() {
        final Response resp = this.head(JAR);
        MatcherAssert.assertThat(
            "HEAD answers the cooldown 403",
            resp.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "HEAD carries the cooldown marker",
            resp.headers().values("X-Pantera-Cooldown").isEmpty(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "gate evaluated the version key",
            this.seen.stream().map(r -> r.artifact() + ":" + r.version()).toList(),
            new IsEqual<>(List.of("com.example.lib:1.0"))
        );
    }

    @Test
    void headOfAChecksumIsNotGated() {
        MatcherAssert.assertThat(
            this.head(JAR + ".sha1").status(), new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    void headOfACachedArtifactIsServedLocally() {
        this.storage.save(
            new Key.From(JAR.substring(1)),
            new Content.From("cached".getBytes(StandardCharsets.UTF_8))
        ).join();
        MatcherAssert.assertThat(
            "cached artifact HEAD is 200 (matches the cache-hit GET)",
            this.head(JAR).status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "no cooldown evaluation on a cache hit",
            this.seen.size(), new IsEqual<>(0)
        );
    }

    private Response head(final String path) {
        final Slice upstream = (line, headers, body) -> ResponseBuilder.ok()
            .header("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
            .header("Content-Length", "6")
            .completedFuture();
        final CachedProxySlice cached = new CachedProxySlice(
            upstream,
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(Optional.empty()),
            Optional.of(new LinkedList<>()), "maven_proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            new BlockingService(this.seen), noopInspector(), Optional.of(this.storage),
            ProxyCacheConfig.withCooldown(),
            new MetadataCache(Duration.ofMinutes(1)),
            null
        );
        return new HeadProxySlice(upstream, Optional.of(this.storage), cached::cooldownAtHeaders)
            .response(new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY)
            .join();
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
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
        };
    }

    /**
     * Cooldown service that blocks everything and records the requests.
     */
    private static final class BlockingService implements CooldownService {
        private final List<CooldownRequest> seen;

        BlockingService(final List<CooldownRequest> seen) {
            this.seen = seen;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return this.evaluateWithKnownDate(request, Optional.empty());
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request, final Optional<Instant> known
        ) {
            this.seen.add(request);
            return CompletableFuture.completedFuture(
                CooldownResult.blocked(
                    new CooldownBlock(
                        request.repoType(), request.repoName(), request.artifact(),
                        request.version(), CooldownReason.FRESH_RELEASE,
                        Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                        List.of()
                    )
                )
            );
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String rtype, final String rname, final String art,
            final String ver, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String rtype, final String rname, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String rtype, final String rname
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
