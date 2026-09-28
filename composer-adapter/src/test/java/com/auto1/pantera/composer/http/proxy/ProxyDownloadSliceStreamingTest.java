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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exploit-regression test for the Composer proxy's whole-artifact upstream
 * buffering (resource-dos F53).
 *
 * <p>On a cache miss {@code ProxyDownloadSlice} called
 * {@code response.body().asBytesFuture()} on the upstream dist archive with
 * no bound — the whole artifact (any size the upstream chose to send) was
 * materialised in heap before the first byte reached the client or
 * storage. The dist must be STREAMED through: the client response is
 * available while the upstream is still delivering, and the bytes are
 * committed to the cache as they arrive.</p>
 *
 * <p>The proof is ordering, never wall-clock: the fake upstream emits one
 * chunk then parks on a latch. With buffering, the response future cannot
 * complete until the latch is released (so the bounded wait below times
 * out); with streaming it completes while the upstream is still parked.</p>
 *
 * @since 2.2.9
 */
final class ProxyDownloadSliceStreamingTest {

    private static final URI UPSTREAM = URI.create("https://upstream.example");

    @Test
    @Timeout(20)
    void distIsStreamedThroughWhileUpstreamIsStillDelivering() throws Exception {
        final InMemoryStorage storage = new InMemoryStorage();
        // Cached packagist metadata pointing the dist at the upstream host.
        storage.save(
            new Key.From("acme/widget.json"),
            new Content.From((
                "{\"packages\":{\"acme/widget\":{\"1.0.0\":{\"version\":\"1.0.0\","
                    + "\"dist\":{\"url\":\"https://upstream.example/dist/acme/widget/1.0.0.zip\"}}}}}"
            ).getBytes(StandardCharsets.UTF_8))
        ).join();
        final byte[] first = "first-chunk-".getBytes(StandardCharsets.UTF_8);
        final byte[] second = "second-chunk".getBytes(StandardCharsets.UTF_8);
        final CountDownLatch release = new CountDownLatch(1);
        final Slice upstream = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok().body(
                new Content.From(
                    Flowable.concat(
                        Flowable.just(ByteBuffer.wrap(first)),
                        // Park until the test releases the upstream.
                        Flowable.fromCallable(() -> {
                            release.await(15, TimeUnit.SECONDS);
                            return ByteBuffer.wrap(second);
                        }).subscribeOn(io.reactivex.schedulers.Schedulers.io())
                    )
                )
            ).build()
        );
        final ProxyDownloadSlice slice = new ProxyDownloadSlice(
            upstream, null, UPSTREAM, Optional.empty(), "composer-proxy",
            "composer-proxy", storage, NoopCooldownService.INSTANCE, new NoDates()
        );
        final CompletableFuture<Response> response = slice.response(
            new RequestLine(RqMethod.GET, "/dist/acme/widget/1.0.0.zip"),
            Headers.EMPTY, Content.EMPTY
        );
        // ORDERING PROOF: the response must be handed to the client while
        // the upstream is still parked on its second chunk.
        final Response served = response.get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the dist must be served with 200 while the upstream is mid-body",
            served.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the upstream must still be parked when the response is available "
                + "(buffering would have needed the whole body first)",
            release.getCount(), new IsEqual<>(1L)
        );
        release.countDown();
        final byte[] delivered = served.body().asBytesFuture().get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the client must receive the complete dist",
            new String(delivered, StandardCharsets.UTF_8),
            new IsEqual<>("first-chunk-second-chunk")
        );
        // The cache commit lands once the stream completes — poll for it.
        final Key dist = new Key.From("dist", "acme", "widget", "1.0.0.zip");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!storage.exists(dist).join()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("streamed dist was never committed to the cache");
            }
            Thread.sleep(5);
        }
        MatcherAssert.assertThat(
            "the cached dist must hold exactly the streamed bytes",
            new String(
                storage.value(dist).join().asBytesFuture().get(5, TimeUnit.SECONDS),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>("first-chunk-second-chunk")
        );
    }

    /**
     * Inspector with no release dates and no dependencies.
     */
    private static final class NoDates implements CooldownInspector {
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
    }
}
