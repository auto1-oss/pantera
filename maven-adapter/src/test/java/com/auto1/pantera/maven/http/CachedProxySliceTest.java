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
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.cache.CachedArtifactMetadataStore;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        "/com/pantera/asto/1.5/asto-1.5.jar",
        "/com/pantera/asto/1.0-SNAPSHOT/asto-1.0-20200520.121003-4.jar",
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
        "/com/pantera/asto/1.5/asto-1.5-sources.jar",
        "/com/pantera/asto/1.0-SNAPSHOT/asto-1.0-20200520.121003-4.jar.sha1",
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

    /**
     * Regression: when a {@link CooldownMetadataService} is provided,
     * {@code handleMetadata()} MUST pass the upstream {@code maven-metadata.xml}
     * bytes through it before responding. Before the wiring fix, the service
     * existed in {@code CooldownAdapterRegistry} but was never invoked on the
     * Maven proxy path — a {@code latest.release} resolve through
     * {@code gradle_group} resolved to fresh-within-cooldown versions
     * (reproduced with {@code com.google.guava:guava:latest.release} picking
     * up {@code 33.6.0-jre} 8 days after release against a 14-day cooldown).
     *
     * <p>The test substitutes a recording stub service; the stub asserts that
     * the filter is invoked with (repoType, repoName, packageName, rawBytes)
     * and returns a deterministic "filtered" body. The response body must
     * equal that body — not the upstream bytes — proving the filter ran.</p>
     */
    @Test
    void handleMetadataInvokesCooldownFilterWhenServiceProvided() throws Exception {
        final byte[] upstreamXml = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<metadata>"
            + "<groupId>com.google.guava</groupId>"
            + "<artifactId>guava</artifactId>"
            + "<versioning><latest>33.6.0-jre</latest><release>33.6.0-jre</release>"
            + "<versions><version>33.5.0-jre</version><version>33.6.0-jre</version></versions>"
            + "</versioning></metadata>"
        ).getBytes(StandardCharsets.UTF_8);
        final byte[] filteredXml = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<metadata>"
            + "<groupId>com.google.guava</groupId>"
            + "<artifactId>guava</artifactId>"
            + "<versioning><latest>33.5.0-jre</latest><release>33.5.0-jre</release>"
            + "<versions><version>33.5.0-jre</version></versions>"
            + "</versioning></metadata>"
        ).getBytes(StandardCharsets.UTF_8);
        final AtomicReference<String> capturedPackage = new AtomicReference<>();
        final AtomicReference<byte[]> capturedBytes = new AtomicReference<>();
        final CooldownMetadataService recordingService = new CooldownMetadataService() {
            @Override
            public <T> CompletableFuture<byte[]> filterMetadata(
                final String repoType, final String repoName, final String packageName,
                final byte[] rawMetadata, final MetadataParser<T> parser,
                final MetadataFilter<T> filter, final MetadataRewriter<T> rewriter,
                final Optional<CooldownInspector> inspector
            ) {
                capturedPackage.set(packageName);
                capturedBytes.set(rawMetadata);
                return CompletableFuture.completedFuture(filteredXml);
            }

            @Override
            public void invalidate(final String repoType, final String repoName, final String packageName) { }

            @Override
            public void invalidateAll(final String repoType, final String repoName) { }

            @Override
            public void clearAll() { }

            @Override
            public String stats() {
                return "recording";
            }
        };
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(upstreamXml).build()
            ),
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(Optional.empty()),
            Optional.of(this.events), "gradle_proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(), NO_STORAGE,
            ProxyCacheConfig.defaults(),
            new MetadataCache(Duration.ofMinutes(1)),
            recordingService
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/com/google/guava/guava/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Filter must be invoked with the group/artifact coordinate",
            capturedPackage.get(),
            Matchers.is("com/google/guava/guava")
        );
        MatcherAssert.assertThat(
            "Filter must receive the raw upstream bytes",
            capturedBytes.get() != null && java.util.Arrays.equals(capturedBytes.get(), upstreamXml),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(response.status(), Matchers.is(RsStatus.OK));
        assertArrayEquals(
            filteredXml,
            response.body().asBytes(),
            "Response body must be the filter's output, not the raw upstream bytes — "
                + "if this fails, handleMetadata() is bypassing the cooldown filter and "
                + "Gradle's latest.release resolution will pick up too-fresh versions"
        );
    }

    /**
     * Absence of a {@link CooldownMetadataService} must preserve the
     * pre-fix pass-through behaviour — used by the legacy 11-arg constructor
     * path and by tests. Pairs with the test above to prove the guard on
     * {@code cooldownMetadata != null} is real, not unconditional.
     */
    @Test
    void handleMetadataPassesThroughWhenNoCooldownMetadataService() throws Exception {
        final byte[] upstreamXml = "<metadata><groupId>x</groupId><artifactId>y</artifactId></metadata>"
            .getBytes(StandardCharsets.UTF_8);
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(upstreamXml).build()
            ),
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(Optional.empty()),
            Optional.of(this.events), "gradle_proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(), NO_STORAGE,
            ProxyCacheConfig.defaults(),
            new MetadataCache(Duration.ofMinutes(1))
            // no CooldownMetadataService → pass-through behaviour
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/x/y/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(response.status(), Matchers.is(RsStatus.OK));
        assertArrayEquals(upstreamXml, response.body().asBytes());
    }
}
