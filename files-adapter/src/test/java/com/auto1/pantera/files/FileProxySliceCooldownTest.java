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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * file-proxy cooldown blocks a fetch whose upstream {@code Last-Modified}
 * lies inside the cooldown window, with the file-proxy 403.
 *
 * @since 2.2.9
 */
final class FileProxySliceCooldownTest {

    /**
     * Requested file.
     */
    private static final String PATH = "/tools/cli-1.0.tar.gz";

    @BeforeEach
    void setUp() {
        CooldownResponseRegistry.instance().register(new FileProxyCooldownResponseFactory());
    }

    @Test
    void freshUpstreamFileIsBlocked() {
        final Upstream upstream = new Upstream(Instant.now().minus(Duration.ofHours(1)));
        final Response resp = slice(upstream).response(
            new RequestLine(RqMethod.GET, PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        resp.body().asBytesFuture().join();
        MatcherAssert.assertThat(
            "fresh file is refused with 403",
            resp.status().code(),
            new IsEqual<>(403)
        );
        MatcherAssert.assertThat(
            "403 carries the cooldown marker",
            resp.headers().values(CooldownResponseFactory.HEADER),
            new IsEqual<>(List.of("blocked"))
        );
        MatcherAssert.assertThat(
            "the file body is never fetched",
            upstream.gets(),
            new IsEqual<>(0L)
        );
    }

    @Test
    void oldUpstreamFileIsServed() {
        final Upstream upstream = new Upstream(Instant.now().minus(Duration.ofDays(30)));
        final Response resp = slice(upstream).response(
            new RequestLine(RqMethod.GET, PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "old file is served",
            new String(resp.body().asBytesFuture().join(), StandardCharsets.UTF_8),
            new IsEqual<>("payload")
        );
        MatcherAssert.assertThat(
            "status 200",
            resp.status().code(),
            new IsEqual<>(200)
        );
    }

    private static FileProxySlice slice(final Slice upstream) {
        return new FileProxySlice(
            upstream, Cache.NOP, Optional.empty(), "files_proxy", "file-proxy",
            new WindowCooldown(), "http://upstream.invalid/", Optional.of(new InMemoryStorage())
        );
    }

    /**
     * Upstream answering HEAD/GET with a fixed Last-Modified.
     */
    private static final class Upstream implements Slice {
        /**
         * Last-Modified value.
         */
        private final String modified;

        /**
         * Methods received.
         */
        private final List<RqMethod> methods = new CopyOnWriteArrayList<>();

        Upstream(final Instant modified) {
            this.modified = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                modified.atOffset(ZoneOffset.UTC)
            );
        }

        long gets() {
            return this.methods.stream().filter(m -> m == RqMethod.GET).count();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.methods.add(line.method());
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .headers(Headers.from(new Header("Last-Modified", this.modified)))
                    .body("payload".getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        }
    }

    /**
     * Cooldown blocking anything the inspector dates within the last 72 h.
     */
    private static final class WindowCooldown implements CooldownService {
        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return inspector.releaseDate(request.artifact(), request.version()).thenApply(
                date -> {
                    final Instant until = date.map(d -> d.plus(Duration.ofHours(72)))
                        .orElse(Instant.EPOCH);
                    if (until.isAfter(Instant.now())) {
                        return CooldownResult.blocked(
                            new CooldownBlock(
                                request.repoType(), request.repoName(), request.artifact(),
                                request.version(), CooldownReason.FRESH_RELEASE,
                                Instant.now(), until, List.of()
                            )
                        );
                    }
                    return CooldownResult.allowed();
                }
            );
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
}
