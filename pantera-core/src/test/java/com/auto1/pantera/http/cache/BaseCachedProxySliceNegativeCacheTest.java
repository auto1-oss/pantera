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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A proxy remembers a 404 of its upstream per file. A Maven version has many
 * files, and clients ask for some that do not exist: the Maven Site plugin
 * looks for {@code <artifact>-<version>-site.xml} next to every parent POM.
 * Such a 404 used to be remembered for the artifact version as a whole, so
 * the proxy then answered 404 for the POM of that version as well, without
 * asking the upstream, for the negative-cache TTL.
 *
 * @since 2.2.12
 */
final class BaseCachedProxySliceNegativeCacheTest {

    /**
     * A file of the version that the upstream has.
     */
    private static final String POM =
        "/org/springframework/boot/spring-boot-dependencies/4.0.6/spring-boot-dependencies-4.0.6.pom";

    /**
     * A file of the same version that the upstream does not have.
     */
    private static final String SITE =
        "/org/springframework/boot/spring-boot-dependencies/4.0.6/spring-boot-dependencies-4.0.6-site.xml";

    @Test
    @Timeout(20)
    void aMissingFileOfAVersionDoesNotHideTheOtherFilesOfThatVersion() throws Exception {
        final Slice slice = BaseCachedProxySliceNegativeCacheTest.proxy(new AtomicInteger());
        final RsStatus site = BaseCachedProxySliceNegativeCacheTest.get(slice, SITE);
        final RsStatus pom = BaseCachedProxySliceNegativeCacheTest.get(slice, POM);
        MatcherAssert.assertThat(
            "the file the upstream does not have is not found",
            site, new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the POM of the same version is still served",
            pom, new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    @Timeout(20)
    void aMissingFileIsAskedOfTheUpstreamOnce() throws Exception {
        final AtomicInteger asked = new AtomicInteger();
        final Slice slice = BaseCachedProxySliceNegativeCacheTest.proxy(asked);
        BaseCachedProxySliceNegativeCacheTest.get(slice, SITE);
        BaseCachedProxySliceNegativeCacheTest.get(slice, SITE);
        MatcherAssert.assertThat(asked.get(), new IsEqual<>(1));
    }

    /**
     * GET a path and consume the answer.
     * @param slice Proxy
     * @param path Path
     * @return Status
     * @throws Exception On error
     */
    private static RsStatus get(final Slice slice, final String path) throws Exception {
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        response.body().asBytesFuture().get(10, TimeUnit.SECONDS);
        return response.status();
    }

    /**
     * A Maven proxy over an upstream that has the POM and nothing else.
     * @param missing Counts the upstream requests for the missing file
     * @return Proxy
     */
    private static Slice proxy(final AtomicInteger missing) {
        final Slice upstream = (line, headers, body) -> {
            final Response response;
            if (line.uri().getPath().endsWith(".pom")) {
                response = ResponseBuilder.ok()
                    .body("<project/>".getBytes(StandardCharsets.UTF_8)).build();
            } else {
                if (line.uri().getPath().endsWith("-site.xml")) {
                    missing.incrementAndGet();
                }
                response = ResponseBuilder.notFound().build();
            }
            return CompletableFuture.completedFuture(response);
        };
        return new MavenShapedProxy(upstream, new InMemoryStorage());
    }

    /**
     * Proxy of Maven type that caches every path through the standard flow.
     */
    private static final class MavenShapedProxy extends BaseCachedProxySlice {

        MavenShapedProxy(final Slice upstream, final Storage storage) {
            super(
                upstream,
                new FromStorageCache(storage),
                "maven-central",
                "maven-proxy",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }
    }
}
