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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.FailedCompletionStage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.CooldownDependency;
import com.auto1.pantera.cooldown.CooldownInspector;
import com.auto1.pantera.cooldown.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.cache.CachedArtifactMetadataStore;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test case for {@link CachedProxySlice}.
 */
final class CachedProxySliceTest {

    /**
     * Artifact events queue.
     */
    private Queue<ProxyArtifactEvent> events;

    /**
     * Optional storage placeholder for tests.
     */
    private static final Optional<Storage> NO_STORAGE = Optional.empty();

    @BeforeEach
    void init() {
        this.events = new LinkedList<>();
    }

    @Test
    void loadsCachedContent() {
        final byte[] data = "cache".getBytes(StandardCharsets.UTF_8);
        final String path = "/com/example/pkg/1.0/pkg-1.0.jar";
        final InMemoryStorage storage = new InMemoryStorage();
        final CachedArtifactMetadataStore store = new CachedArtifactMetadataStore(storage);
        final Key key = new Key.From(path.substring(1));
        store.save(
            key,
            Headers.from("Content-Length", String.valueOf(data.length)),
            new CachedArtifactMetadataStore.ComputedDigests(data.length, Map.of())
        ).join();
        final AtomicBoolean upstream = new AtomicBoolean(false);
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> {
                upstream.set(true);
                return CompletableFuture.failedFuture(new AssertionError("Upstream should not be hit on cache hit"));
            },
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(
                Optional.of(new Content.From(data))
            ),
            Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE,
            noopInspector(),
            Optional.of(storage)
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat("Upstream should not be called on cache hit", upstream.get(), Matchers.is(false));
        MatcherAssert.assertThat(response.status(), Matchers.is(RsStatus.OK));
        assertArrayEquals(data, response.body().asBytes());
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @Test
    void returnsNotFoundOnRemoteError() {
        MatcherAssert.assertThat(
            new CachedProxySlice(
                new SliceSimple(ResponseBuilder.internalError().build()),
                (key, supplier, control) -> supplier.get(),
                Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE,
                noopInspector(),
                CachedProxySliceTest.NO_STORAGE
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/any")
            )
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @Test
    void returnsNotFoundOnRemoteAndCacheError() {
        MatcherAssert.assertThat(
            new CachedProxySlice(
                new SliceSimple(ResponseBuilder.internalError().build()),
                (key, supplier, control)
                    -> new FailedCompletionStage<>(new RuntimeException("Any error")),
                Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE,
                noopInspector(),
                CachedProxySliceTest.NO_STORAGE
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/abc")
            )
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/com/artipie/asto/1.5/asto-1.5.jar",
        "/com/artipie/asto/1.0-SNAPSHOT/asto-1.0-20200520.121003-4.jar",
        "/org/apache/commons/3.6/commons-3.6.pom",
        "/org/test/test-app/0.95/test-app-3.6.war"
    })
    void loadsOriginAndAdds(final String path) {
        final byte[] data = "remote".getBytes();
        MatcherAssert.assertThat(
            new CachedProxySlice(
                (line, headers, body) -> ResponseBuilder.ok().body(data).completedFuture(),
                (key, supplier, control) -> supplier.get(),
                Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE,
                noopInspector(),
                CachedProxySliceTest.NO_STORAGE
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(data)
                ),
                new RequestLine(RqMethod.GET, path)
            )
        );
        MatcherAssert.assertThat("Events queue has one item", this.events.size() == 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/com/artipie/asto/1.5/asto-1.5-sources.jar",
        "/com/artipie/asto/1.0-SNAPSHOT/asto-1.0-20200520.121003-4.jar.sha1",
        "/org/apache/commons/3.6/commons-3.6-javadoc.pom",
        "/org/test/test-app/maven-metadata.xml"
    })
    void loadsOriginAndDoesNotAddToEvents(final String path) {
        final byte[] data = "remote".getBytes();
        MatcherAssert.assertThat(
            new CachedProxySlice(
                (line, headers, body) -> ResponseBuilder.ok().body(data).completedFuture(),
                (key, supplier, control) -> supplier.get(),
                Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE,
                noopInspector(),
                CachedProxySliceTest.NO_STORAGE
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(data)
                ),
                new RequestLine(RqMethod.GET, path)
            )
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
