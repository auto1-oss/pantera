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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
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
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A group walk that ends in 502 records, per failed member, why it fell
 * through — including a member whose slice failed exceptionally, which used
 * to leave no record at all — correlated with the request's trace.id.
 *
 * @since 2.2.11
 */
final class GroupMemberFailureLogTest {

    /**
     * Trace id of the request.
     */
    private static final String TRACE = "5f1c2a9e7d3b4c8a9e0f1a2b3c4d5e6f";

    /**
     * Capture of the group logger.
     */
    private Capture capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearMap();
        this.capture = Capture.of("com.auto1.pantera.group");
    }

    @AfterEach
    void tearDown() {
        this.capture.close();
        ThreadContext.clearMap();
    }

    @Test
    void everyFailedMemberLeavesItsCause() {
        final Slice timeout = (line, headers, body) -> CompletableFuture.failedFuture(
            new SocketTimeoutException("Read timed out from mirror.internal.corp")
        );
        final Slice broken = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.badGateway().textBody("Upstream temporarily unavailable").build()
        );
        final GroupResolver resolver = new GroupResolver(
            "libs-release",
            List.of(new MemberSlice("slow", timeout, true), new MemberSlice("down", broken, true)),
            Collections.emptyList(),
            Optional.empty(),
            "maven",
            Set.of("slow", "down"),
            new NegativeCache(
                new NegativeCacheConfig(
                    Duration.ofMinutes(5), 10_000, false,
                    NegativeCacheConfig.DEFAULT_L1_MAX_SIZE,
                    NegativeCacheConfig.DEFAULT_L1_TTL,
                    NegativeCacheConfig.DEFAULT_L2_MAX_SIZE,
                    NegativeCacheConfig.DEFAULT_L2_TTL
                )
            ),
            ForkJoinPool.commonPool()
        );
        final Response rsp = resolver.response(
            new RequestLine("GET", "/com/example/x/1.0/x-1.0.jar"),
            new Headers().add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, TRACE),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "every member failed: 502", rsp.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the group body names no member cause",
            new String(rsp.body().asBytes(), StandardCharsets.UTF_8),
            new IsNot<>(new StringContains("mirror.internal.corp"))
        );
        final List<Map<String, Object>> records = this.capture.action("group_member_fallthrough");
        MatcherAssert.assertThat("one record per failed member", records.size(), new IsEqual<>(2));
        final Map<String, Object> thrown = records.get(0);
        MatcherAssert.assertThat(
            "exception member: reason", thrown.get("event.reason"),
            new IsEqual<>("read_timeout")
        );
        MatcherAssert.assertThat(
            "exception member: error.type", thrown.get("error.type"),
            new IsEqual<>("java.net.SocketTimeoutException")
        );
        MatcherAssert.assertThat(
            "exception member: trace.id", thrown.get("trace.id"), new IsEqual<>(TRACE)
        );
        final Map<String, Object> answered = records.get(1);
        MatcherAssert.assertThat(
            "5xx member: reason", answered.get("event.reason"),
            new IsEqual<>("member_status_502")
        );
        MatcherAssert.assertThat(
            "5xx member: status", answered.get("http.response.status_code"),
            new IsEqual<>(502)
        );
        MatcherAssert.assertThat(
            "5xx member: trace.id", answered.get("trace.id"), new IsEqual<>(TRACE)
        );
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
         * Level before.
         */
        private final Level before;

        /**
         * Ctor.
         * @param logger Logger
         */
        private Capture(final Logger logger) {
            super("GroupMemberFailureLogTest", null, null, true, Property.EMPTY_ARRAY);
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
                    if (action.equals(String.valueOf(event.get("event.action")))
                        && "failure".equals(String.valueOf(event.get("event.outcome")))) {
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
            this.logger.setLevel(this.before);
            this.stop();
        }
    }
}
