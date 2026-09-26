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
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.ThreadContext;
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

    @Test
    @Timeout(20)
    void failedBackgroundRefreshIsNotReportedAsA502() throws Exception {
        final InMemoryStorage mem = new InMemoryStorage();
        mem.save(
            new Key.From("acme/bar.json"),
            new Content.From(
                "{\"packages\":{\"acme/bar\":{}}}".getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        final Storage storage = new Aged(mem, Duration.ofHours(13));
        final Slice down = (line, headers, body) -> CompletableFuture.completedFuture(
            com.auto1.pantera.http.ResponseBuilder.from(
                com.auto1.pantera.http.RsStatus.SERVICE_UNAVAILABLE
            ).build()
        );
        try (LogCapture logs = LogCapture.of("com.auto1.pantera.composer")) {
            final Response resp = new CachedProxySlice(
                down,
                new AstoRepository(storage),
                new ComposerStorageCache(new AstoRepository(storage)),
                Optional.empty(),
                "php_proxy",
                "http://localhost:8080/php_proxy",
                "https://packagist.example"
            ).response(
                new RequestLine(RqMethod.GET, "/p2/acme/bar.json"),
                Headers.from("X-Pantera-Ctx-Trace-Id", "trace-r27"),
                Content.EMPTY
            ).join();
            MatcherAssert.assertThat(
                "the client is served the stale copy",
                resp.status().code(), new IsEqual<>(200)
            );
            // Filter by package: a refresh started by another test in this
            // JVM (acme/foo) can log inside this capture window.
            while (ComposerStaleMetadataOfflineTest.refreshOf(logs, "acme/bar").isEmpty()) {
                Thread.sleep(5);
            }
            final Map<String, Object> refresh =
                ComposerStaleMetadataOfflineTest.refreshOf(logs, "acme/bar").get();
            MatcherAssert.assertThat(
                "the failed background refresh is reported as a failure",
                refresh.get("event.outcome"), new IsEqual<>("failure")
            );
            MatcherAssert.assertThat(
                "the refresh log carries the request's trace id",
                refresh.get("trace.id"), new IsEqual<>("trace-r27")
            );
            MatcherAssert.assertThat(
                "no log claims a 502 the client never got",
                logs.action("metadata_fetch").stream().anyMatch(
                    event -> String.valueOf(event.get("message")).contains("502")
                ),
                new IsEqual<>(false)
            );
            MatcherAssert.assertThat(
                "the upstream status log carries the request's trace id",
                logs.action("remote_fetch").stream()
                    .filter(event -> "failure".equals(event.get("event.outcome")))
                    .findFirst().map(event -> event.get("trace.id")).orElse(null),
                new IsEqual<>("trace-r27")
            );
        }
    }

    @Test
    @Timeout(20)
    void backgroundRefreshThroughTheProxyWiringCarriesTheRequestTrace() throws Exception {
        final InMemoryStorage mem = new InMemoryStorage();
        mem.save(
            new Key.From("acme/wired.json"),
            new Content.From(
                "{\"packages\":{\"acme/wired\":{}}}".getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        final Storage storage = new Aged(mem, Duration.ofHours(13));
        final Slice down = (line, headers, body) -> CompletableFuture.completedFuture(
            com.auto1.pantera.http.ResponseBuilder.from(
                com.auto1.pantera.http.RsStatus.SERVICE_UNAVAILABLE
            ).build()
        );
        final ComposerProxySlice proxy = new ComposerProxySlice(
            new StaticClients(down),
            URI.create("https://repo.packagist.example"),
            new AstoRepository(storage),
            Authenticator.ANONYMOUS,
            new ComposerStorageCache(new AstoRepository(storage)),
            Optional.empty(),
            "php_proxy",
            "php-proxy",
            NoopCooldownService.INSTANCE,
            new NoDates(),
            "http://pantera.example:8080/test_prefix/api/php_proxy"
        );
        try (LogCapture logs = LogCapture.of("com.auto1.pantera.composer")) {
            final Response resp = proxy.response(
                new RequestLine(RqMethod.GET, "/p2/acme/wired.json"),
                Headers.from("X-Pantera-Ctx-Trace-Id", "trace-wired"),
                Content.EMPTY
            ).join();
            MatcherAssert.assertThat(
                "the client is served the stale copy",
                resp.status().code(), new IsEqual<>(200)
            );
            while (ComposerStaleMetadataOfflineTest.refreshOf(logs, "acme/wired").isEmpty()) {
                Thread.sleep(5);
            }
            MatcherAssert.assertThat(
                "the refresh log carries the originating request's trace id",
                ComposerStaleMetadataOfflineTest.refreshOf(logs, "acme/wired")
                    .get().get("trace.id"),
                new IsEqual<>("trace-wired")
            );
            MatcherAssert.assertThat(
                "the upstream status log carries the originating request's trace id",
                logs.action("remote_fetch").stream()
                    .filter(event -> "failure".equals(event.get("event.outcome")))
                    .filter(event -> String.valueOf(event.get("url.path")).contains("wired"))
                    .findFirst().map(event -> event.get("trace.id")).orElse(null),
                new IsEqual<>("trace-wired")
            );
        } finally {
            ThreadContext.clearMap();
        }
    }

    @Test
    @Timeout(20)
    void backgroundRefreshNeverInheritsAPooledThreadsStaleTrace() throws Exception {
        final InMemoryStorage mem = new InMemoryStorage();
        mem.save(
            new Key.From("acme/stale.json"),
            new Content.From(
                "{\"packages\":{\"acme/stale\":{}}}".getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        final Storage storage = new Aged(mem, Duration.ofHours(13));
        final Slice down = (line, headers, body) -> CompletableFuture.completedFuture(
            com.auto1.pantera.http.ResponseBuilder.from(
                com.auto1.pantera.http.RsStatus.SERVICE_UNAVAILABLE
            ).build()
        );
        ComposerStaleMetadataOfflineTest.poolTrace("trace-of-an-earlier-request");
        ThreadContext.clearMap();
        ThreadContext.put("trace.id", "trace-current");
        try (LogCapture logs = LogCapture.of("com.auto1.pantera.composer")) {
            new CachedProxySlice(
                down,
                new AstoRepository(storage),
                new ComposerStorageCache(new AstoRepository(storage)),
                Optional.empty(),
                "php_proxy",
                "http://localhost:8080/php_proxy",
                "https://packagist.example"
            ).response(
                new RequestLine(RqMethod.GET, "/p2/acme/stale.json"),
                Headers.EMPTY,
                Content.EMPTY
            ).join();
            while (ComposerStaleMetadataOfflineTest.refreshOf(logs, "acme/stale").isEmpty()) {
                Thread.sleep(5);
            }
            MatcherAssert.assertThat(
                ComposerStaleMetadataOfflineTest.refreshOf(logs, "acme/stale")
                    .get().get("trace.id"),
                new IsEqual<>("trace-current")
            );
        } finally {
            ThreadContext.clearMap();
            ComposerStaleMetadataOfflineTest.poolTrace(null);
        }
    }

    /**
     * Leave (or clear) a trace id in the MDC of every common-pool worker, as
     * an earlier request that did not clean up would.
     * @param trace Trace id to leave behind, or null to clear it
     * @throws InterruptedException If interrupted
     */
    private static void poolTrace(final String trace) throws InterruptedException {
        final int workers = ForkJoinPool.commonPool().getParallelism();
        final CountDownLatch all = new CountDownLatch(workers);
        final CountDownLatch done = new CountDownLatch(workers);
        for (int idx = 0; idx < workers; idx += 1) {
            ForkJoinPool.commonPool().execute(
                () -> {
                    if (trace == null) {
                        ThreadContext.remove("trace.id");
                    } else {
                        ThreadContext.put("trace.id", trace);
                    }
                    all.countDown();
                    try {
                        all.await(5, TimeUnit.SECONDS);
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    done.countDown();
                }
            );
        }
        done.await(10, TimeUnit.SECONDS);
    }

    /**
     * Client slices that always return the same slice.
     */
    private static final class StaticClients implements ClientSlices {
        private final Slice slice;

        StaticClients(final Slice slice) {
            this.slice = slice;
        }

        @Override
        public Slice http(final String host) {
            return this.slice;
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.slice;
        }

        @Override
        public Slice https(final String host) {
            return this.slice;
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.slice;
        }
    }

    /**
     * Inspector with no release dates.
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

    /**
     * The stale-while-revalidate log line of one package, if logged yet.
     * @param logs Captured logs
     * @param pkg Package name
     * @return Event payload
     */
    private static Optional<Map<String, Object>> refreshOf(
        final LogCapture logs, final String pkg
    ) {
        return logs.action("stale_while_revalidate").stream()
            .filter(event -> pkg.equals(event.get("package.name")))
            .findFirst();
    }
}
