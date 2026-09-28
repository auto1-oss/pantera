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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.junit.jupiter.api.Test;

/**
 * A pypi-proxy cache hit on a primary artifact is an artifact serve and must
 * leave an {@code artifact_access} audit record, like the cache-miss fetch.
 *
 * @since 2.2.9
 */
final class CachedPyProxySliceCacheHitAuditTest {

    /**
     * Cached wheel path.
     */
    private static final String WHEEL =
        "packages/ab/cd/0123/pyjokes-0.5.0-py2.py3-none-any.whl";

    @Test
    void cacheHitOfAPrimaryArtifactIsAudited() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(WHEEL), new Content.From(new byte[12])).join();
        final AtomicInteger upstream = new AtomicInteger();
        final Slice origin = (line, headers, body) -> {
            upstream.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        };
        final CachedPyProxySlice slice = new CachedPyProxySlice(
            origin, Optional.of(storage), Duration.ofHours(24), true,
            "pypi_proxy", "https://files.example", "pypi-proxy"
        );
        final Capture audit = Capture.of("artifact.audit");
        try {
            ThreadContext.clearMap();
            final Response resp = slice.response(
                new RequestLine(RqMethod.GET, "/" + WHEEL),
                new Headers()
                    .add(AuthzSlice.LOGIN_HDR, "alice")
                    .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-hit")
                    .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.1.2.3"),
                Content.EMPTY
            ).join();
            resp.body().asBytesFuture().join();
            final List<Map<String, Object>> access = audit.action("artifact_access");
            MatcherAssert.assertThat(
                "the cached copy is served without an upstream call",
                upstream.get(), new IsEqual<>(0)
            );
            MatcherAssert.assertThat(
                "exactly one access record is written",
                access.size(), new IsEqual<>(1)
            );
            final Map<String, Object> rec = access.get(0);
            MatcherAssert.assertThat(
                "the record carries the package identity",
                List.of(
                    rec.get("package.name"), rec.get("package.version"),
                    rec.get("package.size"), rec.get("repository.name"),
                    rec.get("repository.type")
                ),
                new IsEqual<>(
                    List.of("pyjokes", "0.5.0", 12L, "pypi_proxy", "pypi-proxy")
                )
            );
            MatcherAssert.assertThat(
                "the record carries the caller and the request correlation",
                List.of(
                    rec.get("user.name"), rec.get("trace.id"),
                    rec.get("client.ip"), rec.get("event.outcome")
                ),
                new IsEqual<>(List.of("alice", "trace-hit", "10.1.2.3", "success"))
            );
        } finally {
            audit.close();
            ThreadContext.clearMap();
        }
    }

    /**
     * Captures the structured payloads of one logger.
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
            super("CacheHitAudit-" + logger.getName(), null, null, true, Property.EMPTY_ARRAY);
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
