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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
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
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * pypi-proxy cache-miss audit records ({@code artifact_resolution} of a
 * simple-index view, {@code artifact_access} of a fetched file) carry the
 * request's own trace id / client IP / user although they are emitted on the
 * upstream HTTP-client thread, which still holds an unrelated earlier
 * request's MDC.
 *
 * @since 2.2.9
 */
final class ProxySliceMissAuditCorrelationTest {

    /**
     * Wheel requested on the miss path.
     */
    private static final String WHEEL = "/example_project-1.2.3-py3-none-any.whl";

    /**
     * Pooled upstream-client thread.
     */
    private ExecutorService client;

    /**
     * Released once the request has returned, so every continuation after the
     * upstream call runs on the pooled client thread.
     */
    private CountDownLatch release;

    /**
     * Audit capture.
     */
    private Capture audit;

    @BeforeEach
    void setUp() {
        this.client = Executors.newSingleThreadExecutor();
        this.release = new CountDownLatch(1);
        this.audit = Capture.of("artifact.audit");
        ThreadContext.clearMap();
    }

    @AfterEach
    void tearDown() {
        this.audit.close();
        this.client.shutdownNow();
        ThreadContext.clearMap();
    }

    @Test
    @Timeout(20)
    void simpleIndexResolutionCarriesTheRequestCorrelation() throws Exception {
        this.serve(
            "/simple/example-project/",
            () -> ResponseBuilder.ok().htmlBody(
                "<html><body><a href=\"https://files.pythonhosted.org/packages/aa/bb/"
                    + "example_project-1.2.3-py3-none-any.whl#sha256=abc\">"
                    + "example_project-1.2.3-py3-none-any.whl</a></body></html>",
                StandardCharsets.UTF_8
            ).build()
        );
        MatcherAssert.assertThat(
            this.correlation("artifact_resolution"),
            new IsEqual<>(List.of(List.of("trace-of-this-request", "10.1.2.3", "alice")))
        );
    }

    @Test
    @Timeout(20)
    void fetchedFileAccessCarriesTheRequestCorrelation() throws Exception {
        this.serve(WHEEL, () -> ResponseBuilder.ok().body(new byte[12]).build());
        MatcherAssert.assertThat(
            this.correlation("artifact_access"),
            new IsEqual<>(List.of(List.of("trace-of-this-request", "10.1.2.3", "alice")))
        );
    }

    @Test
    @Timeout(20)
    void notFoundAccessCarriesTheRequestCorrelation() throws Exception {
        this.serve(WHEEL, () -> ResponseBuilder.notFound().build());
        MatcherAssert.assertThat(
            this.correlation("artifact_access"),
            new IsEqual<>(List.of(List.of("trace-of-this-request", "10.1.2.3", "alice")))
        );
    }

    /**
     * Serve one request whose upstream answers on the stale client thread.
     *
     * @param path Request path
     * @param answer Upstream answer
     * @throws Exception On failure
     */
    private void serve(final String path, final Supplier<Response> answer) throws Exception {
        final Slice origin = (line, headers, body) -> CompletableFuture.supplyAsync(
            () -> {
                ProxySliceMissAuditCorrelationTest.stale("trace-of-another-request");
                try {
                    this.release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return answer.get();
            },
            this.client
        );
        final CompletableFuture<Response> pending = ProxySliceMissAuditCorrelationTest
            .proxy(origin)
            .response(
                new RequestLine(RqMethod.GET, path),
                new Headers()
                    .add(AuthzSlice.LOGIN_HDR, "alice")
                    .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-of-this-request")
                    .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.1.2.3"),
                Content.EMPTY
            );
        this.release.countDown();
        pending.get(10, TimeUnit.SECONDS).body().asBytesFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Trace id, client IP and user of every record of one action.
     *
     * @param action ECS event.action
     * @return Correlation per record
     */
    private List<List<Object>> correlation(final String action) {
        final List<List<Object>> out = new ArrayList<>();
        for (final Map<String, Object> rec : this.audit.action(action)) {
            out.add(List.of(rec.get("trace.id"), rec.get("client.ip"), rec.get("user.name")));
        }
        return out;
    }

    private static void stale(final String trace) {
        ThreadContext.put(EcsMdc.TRACE_ID, trace);
        ThreadContext.put(EcsMdc.CLIENT_IP, "192.0.2.99");
        ThreadContext.put(EcsMdc.USER_NAME, "mallory");
    }

    private static ProxySlice proxy(final Slice origin) {
        final InMemoryStorage storage = new InMemoryStorage();
        return new ProxySlice(
            new NoClients(),
            Authenticator.ANONYMOUS,
            origin,
            storage,
            new FromStorageCache(storage),
            Optional.empty(),
            "pypi_proxy",
            "pypi-proxy",
            NoopCooldownService.INSTANCE,
            new RegistryBackedInspector("pypi", PublishDateRegistries.instance()),
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            )
        );
    }

    /**
     * Mirror clients; the requests under test never reach a mirror.
     */
    private static final class NoClients implements ClientSlices {
        @Override
        public Slice http(final String host) {
            return NoClients.none();
        }

        @Override
        public Slice http(final String host, final int port) {
            return NoClients.none();
        }

        @Override
        public Slice https(final String host) {
            return NoClients.none();
        }

        @Override
        public Slice https(final String host, final int port) {
            return NoClients.none();
        }

        private static Slice none() {
            return (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }
    }

    /**
     * Captures the fields of one logger as the ECS layout writes them: the
     * payload overlaid with the thread context (MDC-owned keys win).
     */
    private static final class Capture extends AbstractAppender {

        /**
         * Captured payloads.
         */
        private final List<Map<String, Object>> events =
            Collections.synchronizedList(new ArrayList<>());

        /**
         * Captured logger.
         */
        private final Logger logger;

        /**
         * Level of the logger before the capture.
         */
        private final Level before;

        private Capture(final Logger logger) {
            super("MissAuditCorrelation-" + logger.getName(), null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
            this.before = logger.getLevel();
        }

        static Capture of(final String name) {
            final Capture capture = new Capture((Logger) LogManager.getLogger(name));
            capture.start();
            capture.logger.addAppender(capture);
            capture.logger.setLevel(Level.DEBUG);
            return capture;
        }

        List<Map<String, Object>> action(final String action) {
            final List<Map<String, Object>> out = new ArrayList<>();
            synchronized (this.events) {
                for (final Map<String, Object> event : this.events) {
                    if (action.equals(String.valueOf(event.get("event.action")))) {
                        out.add(event);
                    }
                }
            }
            return out;
        }

        @Override
        public void append(final LogEvent event) {
            if (event.getMessage() instanceof MapMessage<?, ?> map) {
                final Map<String, Object> data = new HashMap<>();
                map.getData().forEach((key, value) -> data.put(String.valueOf(key), value));
                data.putAll(event.getContextData().toMap());
                this.events.add(data);
            }
        }

        void close() {
            this.logger.removeAppender(this);
            this.logger.setLevel(this.before);
            this.stop();
        }
    }
}
