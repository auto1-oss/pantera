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
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.fake.FakeManifests;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.proxy.ProxyManifests;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.LocalRefusal;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.ReasonSlice;
import java.net.ConnectException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A manifest that could not be fetched is not a manifest that does not
 * exist: only an upstream that says so is answered 404 MANIFEST_UNKNOWN.
 *
 * @since 2.2.14
 */
final class ManifestSlicesUpstreamFailureTest {

    @Test
    @Timeout(20)
    void getOfAManifestTheUpstreamFailedOnIsBadGateway() throws Exception {
        final Response answer = ManifestSlicesUpstreamFailureTest.ask(
            "GET",
            ManifestSlicesUpstreamFailureTest.proxy(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.internalError().build()
                )
            )
        );
        MatcherAssert.assertThat(
            "an upstream 500 is not a missing manifest",
            answer.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("upstream_status_500"))
        );
    }

    @Test
    @Timeout(20)
    void headOfAManifestTheUpstreamThrottledIsUnavailable() throws Exception {
        final Response answer = ManifestSlicesUpstreamFailureTest.ask(
            "HEAD",
            ManifestSlicesUpstreamFailureTest.proxy(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "7").build()
                )
            )
        );
        MatcherAssert.assertThat(
            "upstream 429 asks the client to repeat",
            answer.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "the upstream's Retry-After is relayed",
            answer.headers().values("Retry-After"), new IsEqual<>(List.of("7"))
        );
    }

    @Test
    @Timeout(20)
    void anOpenCircuitKeepsItsMarker() throws Exception {
        final Response answer = ManifestSlicesUpstreamFailureTest.ask(
            "GET",
            ManifestSlicesUpstreamFailureTest.proxy(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.badGateway()
                        .header(UpstreamCircuitOpenException.HEADER, "true")
                        .header("Retry-After", "30").build()
                )
            )
        );
        MatcherAssert.assertThat(
            "a group must be able to skip this member: the refusal is marked",
            new LocalRefusal(answer).present(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the open breaker is the reason",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("circuit_open"))
        );
    }

    @Test
    @Timeout(20)
    void aFailedCallIsBadGateway() throws Exception {
        final Response answer = ManifestSlicesUpstreamFailureTest.ask(
            "HEAD",
            ManifestSlicesUpstreamFailureTest.proxy(
                (line, headers, body) -> CompletableFuture.failedFuture(
                    new ConnectException("Connection refused")
                )
            )
        );
        MatcherAssert.assertThat(
            "a refused connection is not a missing manifest",
            answer.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the transport failure is the reason",
            new AnswerReason(answer).token(), new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    @Test
    @Timeout(20)
    void aManifestTheUpstreamDoesNotHaveIsStillNotFound() throws Exception {
        final Response answer = ManifestSlicesUpstreamFailureTest.ask(
            "GET", new FakeManifests("empty", "origin")
        );
        MatcherAssert.assertThat(answer.status(), new IsEqual<>(RsStatus.NOT_FOUND));
    }

    private static Manifests proxy(final Slice upstream) {
        return new ProxyManifests(upstream, "test");
    }

    private static Response ask(final String method, final Manifests manifests) throws Exception {
        final Docker docker = new OneRepoDocker(manifests);
        final Slice slice = "HEAD".equals(method)
            ? new HeadManifestSlice(docker) : new GetManifestSlice(docker);
        final Response answer = new ReasonSlice(slice).response(
            new RequestLine(method, "/v2/test/manifests/latest", "HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        answer.body().asBytesFuture().get(10, TimeUnit.SECONDS);
        return answer;
    }

    /**
     * Registry whose every repository reads these manifests.
     */
    private static final class OneRepoDocker implements Docker {

        /**
         * Manifests of every repository.
         */
        private final Manifests manifests;

        /**
         * Ctor.
         * @param manifests Manifests of every repository
         */
        OneRepoDocker(final Manifests manifests) {
            this.manifests = manifests;
        }

        @Override
        public String registryName() {
            return "test";
        }

        @Override
        public Repo repo(final String name) {
            final Manifests held = this.manifests;
            return new Repo() {
                @Override
                public Layers layers() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Manifests manifests() {
                    return held;
                }

                @Override
                public Uploads uploads() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public CompletableFuture<Catalog> catalog(final Pagination pagination) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
