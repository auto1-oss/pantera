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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.RequestReasons;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.ConnectException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A Go proxy tells an upstream that has no such file from an upstream that
 * could not be asked: only the first is a 404.
 *
 * @since 2.2.14
 */
final class CachedProxySliceUpstreamFailureTest {

    /**
     * A {@code .mod} request, served by the cache-load path.
     */
    private static final String MOD = "/filippo.io/edwards25519/@v/v1.1.0.mod";

    @Test
    void upstreamNotFoundIsNotFoundWithUpstreamReason() {
        final Answer answer = this.get(
            CachedProxySliceUpstreamFailureTest.MOD,
            () -> CompletableFuture.completedFuture(ResponseBuilder.notFound().build())
        );
        MatcherAssert.assertThat(
            "upstream 404 stays a 404", answer.status, new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the access record says the upstream answered it",
            answer.noted, new IsEqual<>(Optional.of("upstream_status_404"))
        );
    }

    @Test
    void upstreamServerErrorIsBadGatewayNotNotFound() {
        final Answer answer = this.get(
            CachedProxySliceUpstreamFailureTest.MOD,
            () -> CompletableFuture.completedFuture(ResponseBuilder.unavailable().build())
        );
        MatcherAssert.assertThat(
            "upstream 503 is not a missing module",
            answer.status, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            answer.noted, new IsEqual<>(Optional.of("upstream_status_503"))
        );
    }

    @Test
    void upstreamThrottlingIsUnavailableWithRetryAfter() {
        final Answer answer = this.get(
            CachedProxySliceUpstreamFailureTest.MOD,
            () -> CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "7").build()
            )
        );
        MatcherAssert.assertThat(
            "upstream 429 asks the client to repeat",
            answer.status, new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "the upstream's Retry-After is relayed",
            answer.headers.values("Retry-After"), new IsEqual<>(List.of("7"))
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            answer.noted, new IsEqual<>(Optional.of("upstream_status_429"))
        );
    }

    @Test
    void failedUpstreamCallIsBadGatewayWithItsCause() {
        final Answer answer = this.get(
            CachedProxySliceUpstreamFailureTest.MOD,
            () -> CompletableFuture.failedFuture(new ConnectException("Connection refused"))
        );
        MatcherAssert.assertThat(
            "a refused connection is not a missing module",
            answer.status, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the transport failure is the reason",
            answer.noted, new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    @Test
    void openCircuitKeepsItsMarkerAndRetryAfter() {
        final Answer answer = this.get(
            CachedProxySliceUpstreamFailureTest.MOD,
            () -> CompletableFuture.completedFuture(
                ResponseBuilder.badGateway()
                    .header(UpstreamCircuitOpenException.HEADER, "true")
                    .header("Retry-After", "30").build()
            )
        );
        MatcherAssert.assertThat(
            "a group must be able to skip this member: the refusal is marked",
            new LocalRefusal(
                new Response(answer.status, answer.headers, Content.EMPTY)
            ).present(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the breaker's Retry-After is relayed",
            answer.headers.values("Retry-After"), new IsEqual<>(List.of("30"))
        );
        MatcherAssert.assertThat(
            "the reason is the open breaker",
            answer.noted, new IsEqual<>(Optional.of("circuit_open"))
        );
    }

    @Test
    void failedFetchLeavesNothingInTheCache() {
        final Storage storage = new InMemoryStorage();
        this.get(
            storage, RqMethod.GET, CachedProxySliceUpstreamFailureTest.MOD,
            () -> CompletableFuture.completedFuture(ResponseBuilder.unavailable().build())
        );
        MatcherAssert.assertThat(
            storage.exists(
                new Key.From(CachedProxySliceUpstreamFailureTest.MOD.substring(1))
            ).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void nonArtifactPathKeepsTheUpstreamFailure() {
        final Answer answer = this.get(
            "/sumdb/sum.golang.org/supported",
            () -> CompletableFuture.completedFuture(ResponseBuilder.internalError().build())
        );
        MatcherAssert.assertThat(
            "upstream 500 on a non-artifact path is not a 404",
            answer.status, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            answer.noted, new IsEqual<>(Optional.of("upstream_status_500"))
        );
    }

    @Test
    void headOfUncachedFileKeepsTheUpstreamFailure() {
        final Answer answer = this.get(
            new InMemoryStorage(), RqMethod.HEAD, CachedProxySliceUpstreamFailureTest.MOD,
            () -> CompletableFuture.completedFuture(ResponseBuilder.unavailable().build())
        );
        MatcherAssert.assertThat(
            "upstream 503 to a HEAD is not a 404",
            answer.status, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            answer.noted, new IsEqual<>(Optional.of("upstream_status_503"))
        );
    }

    @Test
    void headThatCannotReachUpstreamIsBadGateway() {
        final Answer answer = this.get(
            new InMemoryStorage(), RqMethod.HEAD, CachedProxySliceUpstreamFailureTest.MOD,
            () -> CompletableFuture.failedFuture(new ConnectException("Connection refused"))
        );
        MatcherAssert.assertThat(
            "a refused connection answers 502, not an escaped exception",
            answer.status, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the transport failure is the reason",
            answer.noted, new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    private Answer get(final String path, final Supplier<CompletableFuture<Response>> upstream) {
        return this.get(new InMemoryStorage(), RqMethod.GET, path, upstream);
    }

    /**
     * Ask a Go proxy whose upstream answers every request the same way.
     * @param storage Cache storage
     * @param method Request method
     * @param path Request path
     * @param upstream Upstream answer
     * @return Status, headers and the reason the request ended with
     */
    private Answer get(
        final Storage storage, final RqMethod method, final String path,
        final Supplier<CompletableFuture<Response>> upstream
    ) {
        final Slice remote = (line, headers, body) -> upstream.get();
        final Slice slice;
        if (method == RqMethod.HEAD) {
            slice = new HeadProxySlice(remote, Optional.of(storage), "go-proxy-test");
        } else {
            slice = new CachedProxySlice(
                remote, new FromStorageCache(storage), Optional.empty(),
                Optional.of(storage), "go-proxy-test", "go-proxy",
                NoopCooldownService.INSTANCE, new NoReleaseDate()
            );
        }
        final String slot = RequestReasons.instance().open();
        final Response resp = slice.response(
            new RequestLine(method, path),
            Headers.from(RequestReasons.SLOT_HEADER, slot),
            Content.EMPTY
        ).join();
        resp.body().asBytesFuture().join();
        final Optional<String> noted = RequestReasons.instance().close(slot);
        return new Answer(
            resp.status(), resp.headers(),
            noted.or(() -> new AnswerReason(resp).token())
        );
    }

    /**
     * What the proxy answered.
     * @param status Status
     * @param headers Headers
     * @param noted Reason noted for the access record
     */
    private record Answer(RsStatus status, Headers headers, Optional<String> noted) {
    }

    /**
     * Inspector that knows no release date.
     */
    private static final class NoReleaseDate implements CooldownInspector {
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
    }
}
