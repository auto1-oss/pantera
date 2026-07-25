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
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.fake.FullTagsManifests;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.hm.ResponseMatcher;
import com.auto1.pantera.http.hm.SliceHasResponse;
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
 * Tags list GET endpoint.
 */
class TagsSliceGetTest {

    @Test
    void shouldReturnTags() {
        final byte[] tags = "{...}".getBytes();
        final FakeDocker docker = new FakeDocker(
            new FullTagsManifests(() -> new Content.From(tags))
        );
        MatcherAssert.assertThat(
            "Responds with tags",
            TestDockerAuth.slice(docker),
            new SliceHasResponse(
                new ResponseMatcher(
                    RsStatus.OK,
                    tags,
                    new ContentLength(tags.length),
                    ContentType.json()
                ),
                new RequestLine(RqMethod.GET, "/v2/my-alpine/tags/list"),
                TestDockerAuth.headers(),
                Content.EMPTY
            )
        );
        MatcherAssert.assertThat(
            "Gets tags for expected repository name",
            docker.capture.get(),
            Matchers.is("my-alpine")
        );
    }

    @Test
    void shouldSupportPagination() {
        final String from = "1.0";
        final int limit = 123;
        final FullTagsManifests manifests = new FullTagsManifests(() -> Content.EMPTY);
        final Docker docker = new FakeDocker(manifests);
        TestDockerAuth.slice(docker).response(
            new RequestLine(
                RqMethod.GET,
                String.format("/v2/my-alpine/tags/list?n=%d&last=%s", limit, from)
            ),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Parses from",
            manifests.capturedFrom(),
            Matchers.is(Optional.of(from))
        );
        MatcherAssert.assertThat(
            "Parses limit",
            manifests.capturedLimit(),
            Matchers.is(limit)
        );
    }

    /**
     * WS4-docker.4: a truncated page must carry {@code Link: <...>; rel="next"}.
     */
    @Test
    void shouldEmitNextLinkWhenTruncated() {
        final byte[] body = "{\"name\":\"my-alpine\",\"tags\":[\"1\",\"2\"]}".getBytes();
        final Tags tags = new Tags() {
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
                return Optional.of("2");
            }
        };
        final Docker docker = new FakeDocker(new FullTagsManifests(tags));
        final Response response = TestDockerAuth.slice(docker).response(
            new RequestLine(RqMethod.GET, "/v2/my-alpine/tags/list?n=2"),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        ResponseAssert.check(
            response, RsStatus.OK,
            new Header("Link", "</v2/my-alpine/tags/list?n=2&last=2>; rel=\"next\"")
        );
    }

    /**
     * WS4-docker.4: the last (non-truncated) page must not carry a {@code Link} header.
     */
    @Test
    void shouldOmitNextLinkWhenNotTruncated() {
        final byte[] body = "{\"name\":\"my-alpine\",\"tags\":[\"1\"]}".getBytes();
        final Tags tags = () -> new Content.From(body);
        final Docker docker = new FakeDocker(new FullTagsManifests(tags));
        final Response response = TestDockerAuth.slice(docker).response(
            new RequestLine(RqMethod.GET, "/v2/my-alpine/tags/list"),
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
     * Docker implementation that returns repository with specified manifests
     * and captures repository name.
     *
     * @since 0.8
     */
    private static class FakeDocker implements Docker {

        /**
         * Repository manifests.
         */
        private final Manifests manifests;

        /**
         * Captured repository name.
         */
        private final AtomicReference<String> capture;

        FakeDocker(final Manifests manifests) {
            this.manifests = manifests;
            this.capture = new AtomicReference<>();
        }

        @Override
        public String registryName() {
            return "test_registry";
        }

        @Override
        public Repo repo(String name) {
            this.capture.set(name);
            return new Repo() {
                @Override
                public Layers layers() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Manifests manifests() {
                    return FakeDocker.this.manifests;
                }

                @Override
                public Uploads uploads() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public CompletableFuture<Catalog> catalog(Pagination pagination) {
            throw new UnsupportedOperationException();
        }
    }
}
