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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cached Composer metadata past its freshness window must still be served
 * when the upstream is down (cache-first, offline-safe): the stale copy is
 * answered at once and a refresh runs in the background.
 *
 * @since 2.2.9
 */
final class ComposerStaleMetadataOfflineTest {

    @Test
    @Timeout(20)
    void staleCachedMetadataIsServedWhileTheUpstreamIsDown() throws Exception {
        final InMemoryStorage mem = new InMemoryStorage();
        final byte[] cached = (
            "{\"packages\":{\"acme/foo\":{\"1.0.0\":{\"name\":\"acme/foo\","
                + "\"version\":\"1.0.0\"}}}}"
        ).getBytes(StandardCharsets.UTF_8);
        // The metadata cache stores <vendor>/<pkg>.json.
        mem.save(new Key.From("acme/foo.json"), new Content.From(cached)).join();
        final Storage storage = new Aged(mem, Duration.ofHours(13));
        final AtomicInteger upstream = new AtomicInteger();
        final Slice down = (line, headers, body) -> {
            upstream.incrementAndGet();
            return CompletableFuture.failedFuture(new java.net.ConnectException("refused"));
        };
        final CachedProxySlice slice = new CachedProxySlice(
            down,
            new AstoRepository(storage),
            new ComposerStorageCache(new AstoRepository(storage)),
            Optional.empty(),
            "php_proxy",
            "http://localhost:8080/php_proxy",
            "https://packagist.example"
        );
        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/p2/acme/foo.json"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "stale cached metadata is served during the outage",
            resp.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the served body is the cached package",
            new String(resp.body().asBytes(), StandardCharsets.UTF_8).contains("acme/foo"),
            new IsEqual<>(true)
        );
        // The stale copy triggers a background refresh attempt.
        while (upstream.get() == 0) {
            Thread.sleep(5);
        }
        MatcherAssert.assertThat(
            "a background refresh contacted the upstream",
            upstream.get() > 0, new IsEqual<>(true)
        );
    }

    /**
     * Storage whose metadata reports every entry as last updated some time ago.
     */
    private static final class Aged extends Storage.Wrap {
        private final Duration age;

        Aged(final Storage delegate, final Duration age) {
            super(delegate);
            this.age = age;
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return super.metadata(key).thenApply(meta -> new Meta() {
                @Override
                public <T> T read(final ReadOperator<T> opr) {
                    final Map<String, String> raw = new HashMap<>(
                        meta.read(Map::copyOf)
                    );
                    raw.put("updated-at", Instant.now().minus(Aged.this.age).toString());
                    return opr.take(raw);
                }
            });
        }
    }
}
