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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The {@code artifact_resolution} audit record of a php-proxy metadata
 * listing carries the request's own trace id, client IP and user, even when
 * it is emitted on a pooled thread that still holds an earlier, unrelated
 * request's MDC.
 *
 * @since 2.2.9
 */
final class ComposerProxyResolutionAuditTest {

    /**
     * Upstream metadata of acme/widget.
     */
    private static final String META =
        "{\"packages\":{\"acme/widget\":[{\"name\":\"acme/widget\","
            + "\"version\":\"1.0.0\",\"time\":\"2020-01-01T00:00:00+00:00\","
            + "\"dist\":{\"type\":\"zip\",\"url\":\"https://up.example/w.zip\"}}]}}";

    @Test
    @Timeout(20)
    void recordCarriesTheRequestCorrelationFromItsHeaders() throws Exception {
        final Map<String, Object> rec = ComposerProxyResolutionAuditTest.resolution(
            new Headers()
                .add(AuthzSlice.LOGIN_HDR, "alice")
                .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-of-this-request")
                .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.1.2.3")
        );
        MatcherAssert.assertThat(
            List.of(rec.get("trace.id"), rec.get("client.ip"), rec.get("user.name")),
            new IsEqual<>(List.of("trace-of-this-request", "10.1.2.3", "alice"))
        );
    }

    @Test
    @Timeout(20)
    void recordNeverInheritsAnotherRequestsCorrelation() throws Exception {
        // No request context reached the slice: the record must say so
        // rather than borrow the trace id / client IP a pooled thread (or
        // the calling thread) still holds from an unrelated request.
        final Map<String, Object> rec = ComposerProxyResolutionAuditTest.resolution(
            new Headers().add(AuthzSlice.LOGIN_HDR, "alice")
        );
        MatcherAssert.assertThat(
            Arrays.asList(rec.get("trace.id"), rec.get("client.ip"), rec.get("user.name")),
            new IsEqual<>(Arrays.asList(null, null, "alice"))
        );
    }

    /**
     * Serve {@code /p2/acme/widget.json} with the upstream answering on a
     * pooled client thread, and return the single resolution record.
     * Both that thread and the calling thread hold an earlier, unrelated
     * request's MDC, as pooled event-loop / HTTP-client threads do.
     *
     * @param headers Request headers
     * @return Resolution record
     * @throws Exception On failure
     */
    private static Map<String, Object> resolution(final Headers headers) throws Exception {
        final ExecutorService client = Executors.newSingleThreadExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        // Answer only once response() has returned, so every continuation
        // after the upstream call runs on the pooled client thread.
        final Slice remote = (line, rqheaders, body) -> CompletableFuture.supplyAsync(
            () -> {
                ComposerProxyResolutionAuditTest.stale("trace-on-client-thread");
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return ResponseBuilder.ok().jsonBody(META).build();
            },
            client
        );
        try (LogCapture audit = LogCapture.of("artifact.audit")) {
            ComposerProxyResolutionAuditTest.stale("trace-on-calling-thread");
            final CompletableFuture<Response> pending = ComposerProxyResolutionAuditTest
                .proxy(remote)
                .response(
                    new RequestLine(RqMethod.GET, "/p2/acme/widget.json"), headers, Content.EMPTY
                );
            release.countDown();
            pending.get(10, TimeUnit.SECONDS).body().asBytesFuture().get(10, TimeUnit.SECONDS);
            final List<Map<String, Object>> recs = audit.action("artifact_resolution");
            MatcherAssert.assertThat(
                "exactly one resolution record is written", recs.size(), new IsEqual<>(1)
            );
            return recs.get(0);
        } finally {
            client.shutdownNow();
            ThreadContext.clearMap();
        }
    }

    private static void stale(final String trace) {
        ThreadContext.put(EcsMdc.TRACE_ID, trace);
        ThreadContext.put(EcsMdc.CLIENT_IP, "192.0.2.99");
        ThreadContext.put(EcsMdc.USER_NAME, "mallory");
    }

    private static ComposerProxySlice proxy(final Slice remote) {
        return new ComposerProxySlice(
            new StaticClients(remote),
            URI.create("https://repo.packagist.example"),
            new AstoRepository(new InMemoryStorage()),
            Authenticator.ANONYMOUS,
            new ComposerStorageCache(new AstoRepository(new InMemoryStorage())),
            Optional.empty(),
            "php_proxy",
            "php-proxy",
            NoopCooldownService.INSTANCE,
            new NoDates(),
            "http://pantera.example:8080/test_prefix/api/php_proxy"
        );
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
}
