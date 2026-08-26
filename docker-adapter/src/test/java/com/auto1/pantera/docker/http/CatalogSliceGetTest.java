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
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.jupiter.api.Test;

import java.util.Optional;
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
     * WS4-docker.4: a truncated page must carry {@code Link: <...>; rel="next"}.
     */
    @Test
    void shouldEmitNextLinkWhenTruncated() {
        final byte[] body = "{\"repositories\":[\"bar\",\"busybox\"]}".getBytes();
        final Catalog catalog = new Catalog() {
            @Override
            public Content json() {
                return new Content.From(body);
            }

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Optional<String> nextCursor() {
                return Optional.of("busybox");
            }
        };
        final Response response = TestDockerAuth.slice(new FakeDocker(catalog)).response(
            new RequestLine(RqMethod.GET, "/v2/_catalog?n=2"),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        ResponseAssert.check(
            response, RsStatus.OK,
            new Header("Link", "</v2/_catalog?n=2&last=busybox>; rel=\"next\"")
        );
    }

    /**
     * WS4-docker.4: the last (non-truncated) page must not carry a {@code Link} header.
     */
    @Test
    void shouldOmitNextLinkWhenNotTruncated() {
        final byte[] body = "{\"repositories\":[\"bar\"]}".getBytes();
        final Catalog catalog = () -> new Content.From(body);
        final Response response = TestDockerAuth.slice(new FakeDocker(catalog)).response(
            new RequestLine(RqMethod.GET, "/v2/_catalog"),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        ResponseAssert.check(response, RsStatus.OK);
        MatcherAssert.assertThat(
            "No Link header expected when the page is not truncated",
            response.headers().find("Link"),
            new IsEmptyCollection<>()
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
