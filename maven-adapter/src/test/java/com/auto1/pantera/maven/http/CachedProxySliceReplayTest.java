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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Troubleshoot replay asks a Maven proxy what a client would get. It
 * must leave the proxy as it found it: a file the upstream has is
 * answered, not stored, and no event for the search index is written.
 * A replay that cached the file changed what the group index says, and
 * with it the answers of the requests that followed.
 *
 * @since 2.2.15
 */
final class CachedProxySliceReplayTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/org/example/app/1.0/app-1.0.jar",
        "/org/example/app/1.0/app-1.0.pom",
        "/org/example/dist/1.0/dist-1.0.tgz"
    })
    @Timeout(120)
    void replayAnswersWhatTheUpstreamHasAndStoresNothing(final String path) throws Exception {
        final byte[] bytes = "artifact bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final AtomicInteger upstream = new AtomicInteger();
        final Slice remote = (line, headers, body) -> {
            final Response res;
            if (line.uri().getPath().equals(path)) {
                upstream.incrementAndGet();
                res = ResponseBuilder.ok().body(bytes).build();
            } else {
                res = ResponseBuilder.notFound().build();
            }
            return CompletableFuture.completedFuture(res);
        };
        final Storage storage = new InMemoryStorage();
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final CachedProxySlice slice = new CachedProxySlice(
            remote, new FromStorageCache(storage), Optional.of(events), "maven_proxy",
            "https://repo1.maven.org/maven2", "maven-proxy", NoopCooldownService.INSTANCE,
            CachedProxySliceReplayTest.noopInspector(), Optional.of(storage)
        );
        final String trace = RequestTrace.instance().open();
        try {
            final Response replay = slice.response(
                new RequestLine(RqMethod.GET, path),
                Headers.from(List.of(new Header(RequestTrace.HEADER, trace))),
                Content.EMPTY
            ).get(60, TimeUnit.SECONDS);
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the replay says the file is there", replay.status(), new IsEqual<>(RsStatus.OK)
            );
        } finally {
            RequestTrace.instance().close(trace);
        }
        // A write would come from a callback after the answer: give one
        // that should not exist the time to show itself.
        final Key key = new Key.From(path.substring(1));
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the replay stores no file", storage.exists(key).join(), new IsEqual<>(false)
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
        MatcherAssert.assertThat(
            "and writes no event for the search index", events.size(), new IsEqual<>(0)
        );
        final Response real = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a client's request after it is served from the upstream",
            real.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(bytes)
        );
        // The commit follows the stream: wait for it, do not time it.
        for (int idx = 0; idx < 400 && !storage.exists(key).join(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "and stored as before", storage.exists(key).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            upstream.get(), new IsEqual<>(2)
        );
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
