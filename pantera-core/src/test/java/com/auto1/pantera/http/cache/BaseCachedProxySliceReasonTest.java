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
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.resilience.AdaptiveBulkheadLimits;
import com.auto1.pantera.http.resilience.RepoBulkhead;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.ReasonSlice;
import java.net.ConnectException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A proxy says why it does not answer 2xx: the upstream's own answer, its
 * negative cache, the failure of the upstream call, a full bulkhead. Served
 * through {@link ReasonSlice}, as every repository is, the answer carries
 * that reason for the access record.
 *
 * @since 2.2.12
 */
final class BaseCachedProxySliceReasonTest {

    /**
     * Requested path.
     */
    private static final String PATH = "/com/example/foo/1.0/foo-1.0-site.xml";

    @Test
    @Timeout(20)
    void theFirstNotFoundIsTheUpstreamsAndTheSecondTheNegativeCaches() throws Exception {
        final Slice proxy = BaseCachedProxySliceReasonTest.proxy(
            (line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.notFound().build())
        );
        final Response first = BaseCachedProxySliceReasonTest.get(proxy);
        final Response second = BaseCachedProxySliceReasonTest.get(proxy);
        MatcherAssert.assertThat(
            "both are 404",
            first.status() == RsStatus.NOT_FOUND && second.status() == RsStatus.NOT_FOUND,
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the first was the upstream's answer",
            new AnswerReason(first).token(), new IsEqual<>(Optional.of("upstream_status_404"))
        );
        MatcherAssert.assertThat(
            "the second came from the negative cache",
            new AnswerReason(second).token(), new IsEqual<>(Optional.of("negative_cache"))
        );
    }

    @Test
    @Timeout(20)
    void anUpstreamClientErrorIsNamedWithItsStatus() throws Exception {
        final Slice proxy = BaseCachedProxySliceReasonTest.proxy(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS).build()
            )
        );
        MatcherAssert.assertThat(
            new AnswerReason(BaseCachedProxySliceReasonTest.get(proxy)).token(),
            new IsEqual<>(Optional.of("upstream_status_429"))
        );
    }

    @Test
    @Timeout(20)
    void anUpstreamServerErrorIsNamedWithItsStatus() throws Exception {
        final Slice proxy = BaseCachedProxySliceReasonTest.proxy(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.INTERNAL_ERROR).build()
            )
        );
        final Response answer = BaseCachedProxySliceReasonTest.get(proxy);
        MatcherAssert.assertThat(
            "the client gets 503",
            answer.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "and the record says the upstream answered 500",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("upstream_status_500"))
        );
    }

    @Test
    @Timeout(20)
    void aFailedUpstreamCallIsNamedByWhatWentWrong() throws Exception {
        final Slice proxy = BaseCachedProxySliceReasonTest.proxy(
            (line, headers, body) ->
                CompletableFuture.failedFuture(new ConnectException("Connection refused"))
        );
        MatcherAssert.assertThat(
            new AnswerReason(BaseCachedProxySliceReasonTest.get(proxy)).token(),
            new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    @Test
    @Timeout(20)
    void aRequestRefusedForLackOfAPermitSaysSo() throws Exception {
        final TestProxy origin = new TestProxy(
            (line, headers, body) -> new CompletableFuture<>(), new InMemoryStorage()
        );
        origin.setBulkhead(
            new RepoBulkhead(
                "test-repo", AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)),
                ForkJoinPool.commonPool(), Duration.ofMillis(20)
            )
        );
        final Slice proxy = new ReasonSlice(origin);
        proxy.response(
            new RequestLine(RqMethod.GET, "/com/example/held/1.0/held-1.0-site.xml"),
            Headers.EMPTY, Content.EMPTY
        );
        final Response refused = BaseCachedProxySliceReasonTest.get(proxy);
        MatcherAssert.assertThat(
            "the request is refused with 503",
            refused.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "and the record says the repository had no free permit",
            new AnswerReason(refused).token(), new IsEqual<>(Optional.of("bulkhead_full"))
        );
    }

    /**
     * GET the path and consume the answer.
     * @param proxy Proxy
     * @return Answer
     * @throws Exception On error
     */
    private static Response get(final Slice proxy) throws Exception {
        final Response answer = proxy.response(
            new RequestLine(RqMethod.GET, BaseCachedProxySliceReasonTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        answer.body().asBytesFuture().get(10, TimeUnit.SECONDS);
        return answer;
    }

    /**
     * A proxy over the upstream, served the way a repository is.
     * @param upstream Upstream
     * @return Proxy
     */
    private static Slice proxy(final Slice upstream) {
        return new ReasonSlice(new TestProxy(upstream, new InMemoryStorage()));
    }

    /**
     * Proxy that caches every path through the standard flow.
     */
    private static final class TestProxy extends BaseCachedProxySlice {

        TestProxy(final Slice upstream, final Storage storage) {
            super(
                upstream,
                new FromStorageCache(storage),
                "test-repo",
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
