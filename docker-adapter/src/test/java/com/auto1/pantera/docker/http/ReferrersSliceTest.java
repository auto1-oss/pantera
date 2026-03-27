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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link ReferrersSlice}.
 */
class ReferrersSliceTest {

    @Test
    void returnsEmptyImageIndex() {
        final DockerSlice slice = TestDockerAuth.slice(new FakeDocker());
        final var response = slice.response(
            new RequestLine(
                RqMethod.GET,
                "/v2/my-repo/referrers/sha256:abc123"
            ),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Status is OK",
            response.status(),
            Matchers.equalTo(RsStatus.OK)
        );
        final String body = new String(
            response.body().asBytes(), StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "Body contains schemaVersion 2",
            body,
            Matchers.containsString("\"schemaVersion\":2")
        );
        MatcherAssert.assertThat(
            "Body contains empty manifests array",
            body,
            Matchers.containsString("\"manifests\":[]")
        );
        MatcherAssert.assertThat(
            "Body contains OCI image index media type",
            body,
            Matchers.containsString(
                "\"mediaType\":\"application/vnd.oci.image.index.v1+json\""
            )
        );
    }

    @Test
    void returnsCorrectContentType() {
        final DockerSlice slice = TestDockerAuth.slice(new FakeDocker());
        final var response = slice.response(
            new RequestLine(
                RqMethod.GET,
                "/v2/my-repo/referrers/sha256:def456"
            ),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Content-Type is OCI image index",
            response.headers().stream()
                .filter(h -> "Content-Type".equalsIgnoreCase(h.getKey()))
                .map(h -> h.getValue())
                .findFirst()
                .orElse(""),
            Matchers.containsString(
                "application/vnd.oci.image.index.v1+json"
            )
        );
    }

    @Test
    void handlesNestedRepoNames() {
        final DockerSlice slice = TestDockerAuth.slice(new FakeDocker());
        final var response = slice.response(
            new RequestLine(
                RqMethod.GET,
                "/v2/library/nginx/referrers/sha256:aabbcc"
            ),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Nested repo name returns OK",
            response.status(),
            Matchers.equalTo(RsStatus.OK)
        );
    }

    private static class FakeDocker implements Docker {
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
            throw new UnsupportedOperationException();
        }
    }
}
