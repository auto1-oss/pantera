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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * An uncached p2 lookup must tell "the upstream says the package does not
 * exist" (404) apart from "the upstream could not answer" (502): an outage
 * or a malformed upstream body is not proof of absence.
 *
 * @since 2.2.9
 */
final class ComposerUpstreamFailureStatusTest {

    @Test
    void unreachableUpstreamIsBadGateway() {
        MatcherAssert.assertThat(
            ComposerUpstreamFailureStatusTest.status(
                (line, headers, body) -> CompletableFuture.failedFuture(
                    new java.net.ConnectException("Connection refused")
                )
            ),
            new IsEqual<>(502)
        );
    }

    @Test
    void upstreamServerErrorIsBadGateway() {
        MatcherAssert.assertThat(
            ComposerUpstreamFailureStatusTest.status(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.from(RsStatus.SERVICE_UNAVAILABLE).build()
                )
            ),
            new IsEqual<>(502)
        );
    }

    @Test
    void malformedUpstreamJsonIsBadGateway() {
        MatcherAssert.assertThat(
            ComposerUpstreamFailureStatusTest.status(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .body("this is { not json".getBytes(StandardCharsets.UTF_8))
                        .build()
                )
            ),
            new IsEqual<>(502)
        );
    }

    @Test
    void upstreamNotFoundStaysNotFound() {
        MatcherAssert.assertThat(
            ComposerUpstreamFailureStatusTest.status(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                )
            ),
            new IsEqual<>(404)
        );
    }

    private static int status(final Slice upstream) {
        final InMemoryStorage storage = new InMemoryStorage();
        return new CachedProxySlice(
            upstream,
            new AstoRepository(storage),
            new ComposerStorageCache(new AstoRepository(storage)),
            Optional.empty(),
            "php_proxy",
            "http://localhost:8080/php_proxy",
            "https://packagist.example"
        ).response(
            new RequestLine(RqMethod.GET, "/p2/acme/down.json"), Headers.EMPTY, Content.EMPTY
        ).join().status().code();
    }
}
