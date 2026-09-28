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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Catalog;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.Uploads;
import com.auto1.pantera.docker.fake.FullTagsManifests;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.docker.misc.TagsPage;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.hm.ResponseMatcher;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
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
     * B81: tags of a name the repository does not hold is 404 NAME_UNKNOWN,
     * not an empty 200 (which a group relayed as "no tags").
     */
    @Test
    void shouldAnswerNameUnknownForUnknownImage() {
        final Response response = TestDockerAuth.slice(
            new FakeDocker(
                new FullTagsManifests(
                    () -> new Content.From("{\"name\":\"nope\",\"tags\":[]}".getBytes())
                )
            )
        ).response(
            new RequestLine(RqMethod.GET, "/v2/nope/tags/list"),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "unknown name is 404 NAME_UNKNOWN",
            response, new IsErrorsResponse(RsStatus.NOT_FOUND, "NAME_UNKNOWN")
        );
        MatcherAssert.assertThat(
            "an authoritative miss may be negative-cached",
            response.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(true)
        );
    }

    /**
     * B81: when a tag source could not be read (upstream failure), an empty
     * list is not proof the name is unknown: the 404 must not be
     * negative-cached by a group.
     */
    @Test
    void shouldMarkNameUnknownAsNonAuthoritativeWhenListingIncomplete() {
        final Response response = TestDockerAuth.slice(
            new FakeDocker(
                new FullTagsManifests(
                    new TagsPage("nope", java.util.List.of(), Pagination.empty(), false)
                )
            )
        ).response(
            new RequestLine(RqMethod.GET, "/v2/nope/tags/list"),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(false)
        );
    }

    /**
     * T06: a hosted repository answers 404 NAME_UNKNOWN for an image it
     * does not hold even when a {@code last} cursor is sent. The empty 200
     * it gave before won a group walk over the proxy member that holds the
     * image, so every page after the first came back empty.
     */
    @Test
    void hostedAnswersNameUnknownForUnknownImageWithCursor() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("repositories/team/img/_manifests/tags/1/current/link"),
            new Content.From("sha256:abc".getBytes())
        ).join();
        MatcherAssert.assertThat(
            TestDockerAuth.slice(new AstoDocker("registry", storage)).response(
                new RequestLine(RqMethod.GET, "/v2/library/alpine/tags/list?n=2&last=2.7"),
                TestDockerAuth.headers(),
                Content.EMPTY
            ).join(),
            new IsErrorsResponse(RsStatus.NOT_FOUND, "NAME_UNKNOWN")
        );
    }

    /**
     * T06: a cursor past the last tag of an image the repository holds is
     * an empty page (200), not NAME_UNKNOWN.
     */
    @Test
    void hostedAnswersEmptyPageForKnownImagePastLastTag() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("repositories/team/img/_manifests/tags/1/current/link"),
            new Content.From("sha256:abc".getBytes())
        ).join();
        final Response response = TestDockerAuth.slice(new AstoDocker("registry", storage))
            .response(
                new RequestLine(RqMethod.GET, "/v2/team/img/tags/list?n=2&last=1"),
                TestDockerAuth.headers(),
                Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "known image past its last tag is 200",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the page is empty",
            new String(response.body().asBytesFuture().join()),
            new IsEqual<>("{\"name\":\"team/img\",\"tags\":[]}")
        );
    }

    /**
     * B81: a full page carries a Link header to the next page.
     */
    @Test
    void shouldLinkNextPageWhenPageIsFull() {
        final Response response = TestDockerAuth.slice(
            new FakeDocker(
                new FullTagsManifests(
                    () -> new Content.From(
                        "{\"name\":\"my-alpine\",\"tags\":[\"1.0\",\"1.1\"]}".getBytes()
                    )
                )
            )
        ).response(
            new RequestLine(RqMethod.GET, "/v2/my-alpine/tags/list?n=2"),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.headers().values("Link"),
            new IsEqual<>(
                java.util.List.of("</v2/my-alpine/tags/list?n=2&last=1.1>; rel=\"next\"")
            )
        );
    }

    @Test
    void shouldNotLinkWhenPageIsNotFull() {
        final Response response = TestDockerAuth.slice(
            new FakeDocker(
                new FullTagsManifests(
                    () -> new Content.From(
                        "{\"name\":\"my-alpine\",\"tags\":[\"1.0\"]}".getBytes()
                    )
                )
            )
        ).response(
            new RequestLine(RqMethod.GET, "/v2/my-alpine/tags/list?n=2"),
            TestDockerAuth.headers(),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.headers().values("Link").isEmpty(), new IsEqual<>(true)
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
