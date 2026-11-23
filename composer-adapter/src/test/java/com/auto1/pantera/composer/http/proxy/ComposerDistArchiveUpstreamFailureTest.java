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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.LocalRefusal;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A dist archive the upstream did not serve: a throttled or unreachable
 * upstream is not a missing archive, and a breaker fast-fail keeps its
 * marker so a group skips this member.
 *
 * @since 2.2.14
 */
final class ComposerDistArchiveUpstreamFailureTest {

    /**
     * Request path of a dist archive.
     */
    private static final String DIST = "/dists/vendor/package/sha/vendor-package-1.0.0.zip";

    @Test
    void throttledUpstreamIsUnavailableWithRetryAfter() {
        final Response answer = ComposerDistArchiveUpstreamFailureTest.get(
            ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS).header("Retry-After", "7").build()
        );
        MatcherAssert.assertThat(
            "upstream 429 is not a missing archive",
            answer.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "the upstream's Retry-After is relayed",
            answer.headers().values("Retry-After"), new IsEqual<>(List.of("7"))
        );
    }

    @Test
    void openCircuitKeepsItsMarker() {
        final Response answer = ComposerDistArchiveUpstreamFailureTest.get(
            ResponseBuilder.badGateway()
                .header(UpstreamCircuitOpenException.HEADER, "true")
                .header("Retry-After", "30").build()
        );
        MatcherAssert.assertThat(
            "a group must be able to skip this member: the refusal is marked",
            new LocalRefusal(answer).present(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the breaker's Retry-After is relayed",
            answer.headers().values("Retry-After"), new IsEqual<>(List.of("30"))
        );
    }

    @Test
    void upstreamRefusalIsANotFoundThatIsNeverRemembered() {
        final Response answer = ComposerDistArchiveUpstreamFailureTest.get(
            ResponseBuilder.unauthorized().build()
        );
        MatcherAssert.assertThat(
            "an upstream 401 concerns Pantera's credentials: the client gets 404",
            answer.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            new AnswerReason(answer).token(),
            new IsEqual<>(Optional.of("upstream_status_401"))
        );
        MatcherAssert.assertThat(
            "a group must not negative-cache it",
            answer.headers().values(NegativeCache.SKIP_HEADER), new IsEqual<>(List.of("true"))
        );
    }

    private static Response get(final Response upstream) {
        final Storage storage = new InMemoryStorage();
        final Slice origin = (line, headers, body) -> CompletableFuture.completedFuture(upstream);
        final Response answer = new CachedProxySlice(
            origin,
            new AstoRepository(storage),
            new FromStorageCache(storage),
            Optional.empty(),
            "composer-proxy-test",
            "http://localhost:8080",
            "https://packagist.example/composer"
        ).response(
            new RequestLine(RqMethod.GET, ComposerDistArchiveUpstreamFailureTest.DIST),
            Headers.EMPTY, Content.EMPTY
        ).join();
        answer.body().asBytesFuture().join();
        return answer;
    }
}
