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
package com.auto1.pantera.audit;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

/**
 * An audit record carries the correlation of the request it describes — the
 * explicit {@link AuditContext} and identity arguments — never whatever an
 * earlier, unrelated request left in the emitting thread's MDC.
 *
 * @since 2.2.9
 */
final class AuditLoggerCorrelationTest {

    /**
     * Trace id of the request being audited.
     */
    private static final String TRACE = "trace-of-this-request";

    /**
     * Client IP of the request being audited.
     */
    private static final String CLIENT = "10.1.2.3";

    /**
     * Trace id a pooled thread still holds from an earlier request.
     */
    private static final String STALE = "trace-of-another-request";

    /**
     * Audit capture.
     */
    private Capture audit;

    /**
     * Thread with stale MDC.
     */
    private ExecutorService pooled;

    @BeforeEach
    void setUp() {
        this.audit = Capture.of("artifact.audit");
        this.pooled = Executors.newSingleThreadExecutor();
        ThreadContext.clearMap();
    }

    @AfterEach
    void tearDown() {
        this.audit.close();
        this.pooled.shutdownNow();
        ThreadContext.clearMap();
    }

    @Test
    void recordEmittedOnAThreadWithStaleMdcCarriesTheRequestCorrelation() {
        CompletableFuture.runAsync(
            () -> {
                AuditLoggerCorrelationTest.stale();
                AuditLogger.resolution(
                    new AuditContext(TRACE, CLIENT), "pypi-proxy", "pypi_proxy",
                    "requests", "alice", List.of()
                );
            },
            this.pooled
        ).join();
        final Map<String, Object> rec = this.single("artifact_resolution");
        MatcherAssert.assertThat(
            "trace.id, client.ip and user.name are the request's, not the thread's",
            List.of(rec.get("trace.id"), rec.get("client.ip"), rec.get("user.name")),
            new IsEqual<>(List.of(TRACE, CLIENT, "alice"))
        );
        MatcherAssert.assertThat(
            "the stale package.version and span.id do not leak into the record",
            List.of(
                rec.containsKey(EcsMdc.PACKAGE_VERSION), rec.containsKey(EcsMdc.SPAN_ID)
            ),
            new IsEqual<>(List.of(false, false))
        );
    }

    @Test
    void accessRecordOnAStaleThreadCarriesTheRequestCorrelation() {
        CompletableFuture.runAsync(
            () -> {
                AuditLoggerCorrelationTest.stale();
                AuditLogger.access(
                    new AuditContext(TRACE, CLIENT), "pypi-proxy", "pypi_proxy",
                    "requests", "2.31.0", 12L, "alice", AuditLogger.OUTCOME_SUCCESS, null
                );
            },
            this.pooled
        ).join();
        final Map<String, Object> rec = this.single("artifact_access");
        MatcherAssert.assertThat(
            List.of(
                rec.get("trace.id"), rec.get("client.ip"), rec.get("user.name"),
                rec.get("package.version")
            ),
            new IsEqual<>(List.of(TRACE, CLIENT, "alice", "2.31.0"))
        );
    }

    @Test
    void missingContextIsNotFilledFromStaleMdc() {
        AuditLoggerCorrelationTest.stale();
        AuditLogger.resolution(
            AuditContext.NONE, "pypi-proxy", "pypi_proxy", "requests", "alice", List.of()
        );
        final Map<String, Object> rec = this.single("artifact_resolution");
        MatcherAssert.assertThat(
            List.of(rec.containsKey("trace.id"), rec.containsKey("client.ip")),
            new IsEqual<>(List.of(false, false))
        );
    }

    @Test
    void emittingThreadKeepsItsOwnMdc() {
        AuditLoggerCorrelationTest.stale();
        AuditLogger.resolution(
            new AuditContext(TRACE, CLIENT), "pypi-proxy", "pypi_proxy",
            "requests", "alice", List.of()
        );
        MatcherAssert.assertThat(
            List.of(
                ThreadContext.get(EcsMdc.TRACE_ID), ThreadContext.get(EcsMdc.CLIENT_IP),
                ThreadContext.get(EcsMdc.USER_NAME), ThreadContext.get(EcsMdc.SPAN_ID)
            ),
            new IsEqual<>(List.of(STALE, "192.0.2.99", "mallory", "stale-span"))
        );
    }

    @Test
    void contextFromHeadersIgnoresTheThreadMdc() {
        AuditLoggerCorrelationTest.stale();
        MatcherAssert.assertThat(
            "the request's context headers are authoritative",
            new AuditContext(
                new Headers()
                    .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, TRACE)
                    .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, CLIENT)
            ),
            new IsEqual<>(new AuditContext(TRACE, CLIENT))
        );
        MatcherAssert.assertThat(
            "absent headers are not filled from the thread's MDC",
            new AuditContext(new Headers()),
            new IsEqual<>(AuditContext.NONE)
        );
    }

    private Map<String, Object> single(final String action) {
        final List<Map<String, Object>> recs = this.audit.action(action);
        MatcherAssert.assertThat(
            "exactly one " + action + " record", recs.size(), new IsEqual<>(1)
        );
        return recs.get(0);
    }

    /**
     * Leave another request's correlation on the current thread, as a
     * pooled worker / HTTP-client thread does.
     */
    private static void stale() {
        ThreadContext.put(EcsMdc.TRACE_ID, STALE);
        ThreadContext.put(EcsMdc.CLIENT_IP, "192.0.2.99");
        ThreadContext.put(EcsMdc.USER_NAME, "mallory");
        ThreadContext.put(EcsMdc.PACKAGE_VERSION, "9.9.9");
        ThreadContext.put(EcsMdc.SPAN_ID, "stale-span");
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
            super("AuditCorrelation-" + logger.getName(), null, null, true, Property.EMPTY_ARRAY);
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
