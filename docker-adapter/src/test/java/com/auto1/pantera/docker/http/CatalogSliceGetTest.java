/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

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
