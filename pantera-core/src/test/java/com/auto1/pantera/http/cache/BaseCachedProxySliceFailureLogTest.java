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
package com.auto1.pantera.http.cache;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link BaseCachedProxySlice} logs the upstream cause of every 503 it
 * answers — also when the stale-copy lookup that follows the upstream
 * failure fails itself.
 *
 * @since 2.2.11
 */
final class BaseCachedProxySliceFailureLogTest {

    /**
     * Trace id of the request.
     */
    private static final String TRACE = "9c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f";

    /**
     * Request path.
     */
    private static final String PATH = "/com/example/foo/1.0/foo-1.0.tar.gz";

    /**
     * Capture.
     */
    private Capture capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearMap();
        this.capture = Capture.of("com.auto1.pantera.failure-log-test");
    }

    @AfterEach
    void tearDown() {
        this.capture.close();
        ThreadContext.clearMap();
    }

    @Test
    void upstreamCauseLoggedWhenStaleLookupFails() {
        final AtomicBoolean upstreamCalled = new AtomicBoolean();
        final Storage storage = new FailingAfterUpstream(new InMemoryStorage(), upstreamCalled);
        final Response rsp = new Proxy(
            (line, headers, body) -> {
                upstreamCalled.set(true);
                return CompletableFuture.failedFuture(
                    new SocketTimeoutException("Read timed out")
                );
            },
            storage, swr(true)
        ).response(
            new RequestLine(RqMethod.GET, PATH),
            new Headers().add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, TRACE),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "still answers 503", rsp.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        final List<Map<String, Object>> records = this.capture.action("proxy_upstream");
        MatcherAssert.assertThat("one cause record", records.size(), new IsEqual<>(1));
        final Map<String, Object> rec = records.get(0);
        MatcherAssert.assertThat(
            "the upstream cause, not the lookup failure",
            rec.get("error.type"), new IsEqual<>("java.net.SocketTimeoutException")
        );
        MatcherAssert.assertThat(
            "reason", rec.get("event.reason"), new IsEqual<>("read_timeout")
        );
        MatcherAssert.assertThat(
            "status", rec.get("http.response.status_code"), new IsEqual<>(503)
        );
        MatcherAssert.assertThat("trace.id", rec.get("trace.id"), new IsEqual<>(TRACE));
    }

    @Test
    void upstream5xxLoggedOnceWithoutStack() {
        final Response rsp = new Proxy(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.INTERNAL_ERROR).build()
            ),
            new InMemoryStorage(), swr(false)
        ).response(
            new RequestLine(RqMethod.GET, PATH),
            new Headers().add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, TRACE),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "503", rsp.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        final List<Map<String, Object>> records = this.capture.action("proxy_upstream");
        MatcherAssert.assertThat("one cause record", records.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "reason", records.get(0).get("event.reason"),
            new IsEqual<>("upstream_status_500")
        );
        MatcherAssert.assertThat(
            "no stack", records.get(0).get("error.stack_trace"), new IsNull<>()
        );
        MatcherAssert.assertThat(
            "upstream URL", records.get(0).get("url.full"),
            new IsEqual<>("http://upstream" + PATH)
        );
    }

    private static ProxyCacheConfig swr(final boolean enabled) {
        return new ProxyCacheConfig(
            Yaml.createYamlMappingBuilder()
                .add("cache",
                    Yaml.createYamlMappingBuilder()
                        .add("stale_while_revalidate",
                            Yaml.createYamlMappingBuilder()
                                .add("enabled", String.valueOf(enabled))
                                .build())
                        .build())
                .build()
        );
    }

    /**
     * Storage whose reads fail once the upstream was called: the stale-copy
     * lookup after an upstream failure breaks.
     */
    private static final class FailingAfterUpstream extends Storage.Wrap {

        /**
         * Whether the upstream was called.
         */
        private final AtomicBoolean upstream;

        FailingAfterUpstream(final Storage origin, final AtomicBoolean upstream) {
            super(origin);
            this.upstream = upstream;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            if (this.upstream.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("disk gone"));
            }
            return super.exists(key);
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            if (this.upstream.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("disk gone"));
            }
            return super.value(key);
        }
    }

    /**
     * Cache that always misses.
     */
    private static final class Miss implements Cache {
        @Override
        public CompletionStage<Optional<? extends Content>> load(
            final Key key, final Remote remote, final CacheControl control
        ) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * Minimal proxy under test (logger {@code com.auto1.pantera.failure-log-test}).
     */
    private static final class Proxy extends BaseCachedProxySlice {

        Proxy(
            final com.auto1.pantera.http.Slice upstream,
            final Storage storage,
            final ProxyCacheConfig config
        ) {
            super(
                upstream, new Miss(), "test-repo", "failure-log-test", "http://upstream",
                Optional.of(storage), Optional.empty(), config
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }
    }

    /**
     * Appender capturing payload + context data of one logger.
     */
    private static final class Capture extends AbstractAppender implements AutoCloseable {

        /**
         * Events.
         */
        private final List<Map<String, Object>> events =
            Collections.synchronizedList(new ArrayList<>());

        /**
         * Logger.
         */
        private final Logger logger;

        /**
         * Ctor.
         * @param logger Logger
         */
        private Capture(final Logger logger) {
            super("BaseCachedProxySliceFailureLogTest", null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
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

        @Override
        public void close() {
            this.logger.removeAppender(this);
            this.stop();
        }
    }
}
