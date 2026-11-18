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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.ReasonSlice;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The Maven proxy says why a primary artifact (a POM, a jar) is not
 * answered 2xx, so that the access record of the request can: what the
 * upstream answered, or how the call to it failed.
 *
 * @since 2.2.12
 */
final class CachedProxySliceReasonTest {

    @BeforeEach
    void init() {
        CooldownResponseRegistry.instance()
            .register("maven-proxy", new MavenCooldownResponseFactory());
    }

    @ParameterizedTest
    @CsvSource({
        "NOT_FOUND,404,upstream_status_404",
        "CONFLICT,404,upstream_status_409",
        "FORBIDDEN,403,upstream_status_403",
        "TOO_MANY_REQUESTS,429,upstream_status_429",
        "INTERNAL_ERROR,502,upstream_status_500"
    })
    void theAnswerOfTheUpstreamIsTheReason(
        final RsStatus upstream, final int answered, final String reason
    ) throws Exception {
        final Response response = CachedProxySliceReasonTest.get(
            (line, headers, body) ->
                CompletableFuture.completedFuture(ResponseBuilder.from(upstream).build())
        );
        MatcherAssert.assertThat(
            "the client gets " + answered,
            response.status().code(), new IsEqual<>(answered)
        );
        MatcherAssert.assertThat(
            "and the record says what the upstream answered",
            new AnswerReason(response).token(), new IsEqual<>(Optional.of(reason))
        );
    }

    @Test
    void aTimeoutOfTheUpstreamCallIsTheReason() throws Exception {
        final Response response = CachedProxySliceReasonTest.get(
            (line, headers, body) ->
                CompletableFuture.failedFuture(new SocketTimeoutException("Read timed out"))
        );
        MatcherAssert.assertThat(
            new AnswerReason(response).token(), new IsEqual<>(Optional.of("read_timeout"))
        );
    }

    /**
     * GET a POM through a Maven proxy served the way a repository is.
     * @param upstream Upstream
     * @return Answer
     * @throws Exception On error
     */
    private static Response get(final Slice upstream) throws Exception {
        final Response response = new ReasonSlice(
            new CachedProxySlice(
                upstream,
                (cacheKey, supplier, control) -> supplier.get(),
                Optional.of(new LinkedList<>()), "maven-proxy",
                "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE, CachedProxySliceReasonTest.noopInspector(),
                Optional.of(new InMemoryStorage())
            )
        ).response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.pom"),
            Headers.EMPTY, Content.EMPTY
        ).get(20, TimeUnit.SECONDS);
        response.body().asBytesFuture().get(20, TimeUnit.SECONDS);
        return response;
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
}
