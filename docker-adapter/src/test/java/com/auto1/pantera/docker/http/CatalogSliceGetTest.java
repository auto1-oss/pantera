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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link DockerSlice}.
 * Catalog GET endpoint.
 */
class CatalogSliceGetTest {

    @Test
    void shouldReturnCatalog() {
        final byte[] catalog = "{...}".getBytes();
        ResponseAssert.check(
            TestDockerAuth.slice(new FakeDocker(() -> new Content.From(catalog)))
                .response(new RequestLine(RqMethod.GET, "/v2/_catalog"), TestDockerAuth.headers(), Content.EMPTY)
                .join(),
            RsStatus.OK,
            catalog,
            new ContentLength(catalog.length),
            ContentType.json()
        );
    }

    /**
     * B78: in path-routed mode the repository name is the first path
     * segment, so the catalog is reachable at {@code /v2/<repo>/_catalog}.
     */
    @Test
    void shouldReturnCatalogUnderRepositoryPrefix() {
        final byte[] catalog = "{...}".getBytes();
        ResponseAssert.check(
            TestDockerAuth.slice(new FakeDocker(() -> new Content.From(catalog)))
                .response(
                    new RequestLine(RqMethod.GET, "/v2/docker-local/_catalog"),
                    TestDockerAuth.headers(), Content.EMPTY
                )
                .join(),
            RsStatus.OK,
            catalog,
            new ContentLength(catalog.length),
            ContentType.json()
        );
    }

    @Test
    void shouldSupportPagination() {
        final String from = "foo";
        final int limit = 123;
        final FakeDocker docker = new FakeDocker(() -> Content.EMPTY);
        TestDockerAuth.slice(docker).response(
            new RequestLine(
                RqMethod.GET,
                String.format("/v2/_catalog?n=%d&last=%s", limit, from)
            ),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Parses from",
            docker.paginationRef.get().last(),
            Matchers.is(from)
        );
        MatcherAssert.assertThat(
            "Parses limit",
            docker.paginationRef.get().limit(),
            Matchers.is(limit)
        );
    }

    /**
     * R32: a {@code last} cursor outside the repository's own namespace is a
     * client error (400 NAME_INVALID), not a 500.
     */
    @ParameterizedTest
    @CsvSource({"zzz", "other/x", "docker-local", "docker-local/UPPER"})
    void rejectsForeignLastCursor(final String last) {
        final Response response = TestDockerAuth.slice(
            new TrimmedDocker(new FakeDocker(() -> Content.EMPTY), "docker-local")
        ).response(
            new RequestLine(RqMethod.GET, "/v2/docker-local/_catalog?last=" + last),
            TestDockerAuth.headers(), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Status is 400",
            response.status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "Body carries NAME_INVALID",
            response.body().asString(),
            new StringContains("NAME_INVALID")
        );
    }

    /**
     * R32: a malformed page size is 400 PAGINATION_NUMBER_INVALID, not 500.
     */
    @ParameterizedTest
    @CsvSource({"abc", "-1", "99999999999999"})
    void rejectsMalformedPageSize(final String size) {
        final Response response = TestDockerAuth.slice(new FakeDocker(() -> Content.EMPTY))
            .response(
                new RequestLine(RqMethod.GET, "/v2/docker-local/_catalog?n=" + size),
                TestDockerAuth.headers(), Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "Status is 400",
            response.status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "Body carries PAGINATION_NUMBER_INVALID",
            response.body().asString(),
            new StringContains("PAGINATION_NUMBER_INVALID")
        );
    }

    /**
     * A full catalog page links to the next one, like a full tags page.
     */
    @Test
    void linksNextPageWhenPageIsFull() {
        final Response response = TestDockerAuth.slice(
            new FakeDocker(
                () -> new Content.From(
                    "{\"repositories\":[\"docker-local/a\",\"docker-local/b/c\"]}".getBytes()
                )
            )
        ).response(
            new RequestLine(RqMethod.GET, "/v2/docker-local/_catalog?n=2"),
            TestDockerAuth.headers(), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.headers().values("Link"),
            new IsEqual<>(
                java.util.List.of(
                    "</v2/docker-local/_catalog?n=2&last=docker-local%2Fb%2Fc>; rel=\"next\""
                )
            )
        );
    }

    @Test
    void doesNotLinkWhenPageIsNotFull() {
        final Response response = TestDockerAuth.slice(
            new FakeDocker(
                () -> new Content.From("{\"repositories\":[\"docker-local/a\"]}".getBytes())
            )
        ).response(
            new RequestLine(RqMethod.GET, "/v2/docker-local/_catalog?n=2"),
            TestDockerAuth.headers(), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.headers().values("Link").isEmpty(),
            new IsEqual<>(true)
        );
    }

    /**
     * Docker implementation with specified catalog.
     * Values of parameters `from` and `limit` from last call of `catalog` method are captured.
     */
    private static class FakeDocker implements Docker {

        private final Catalog catalog;

        /**
         * From parameter captured.
         */
        private final AtomicReference<Pagination> paginationRef;

        FakeDocker(Catalog catalog) {
            this.catalog = catalog;
            this.paginationRef = new AtomicReference<>();
        }

        @Override
        public String registryName() {
            return "test_registry";
        }

        @Override
        public Repo repo(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Catalog> catalog(Pagination pagination) {
            this.paginationRef.set(pagination);
            return CompletableFuture.completedFuture(this.catalog);
        }
    }
}
