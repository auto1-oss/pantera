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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.RequestReasons;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The access record of every request answered with a status of 400 or above
 * says why, in {@code event.reason}. Without it a 404 from the negative
 * cache and a 404 of the upstream are the same record, and so are a 502
 * after a lost connection and a 502 for a full bulkhead: on 2026-11-17 that
 * left 17,000 answers of one hour unexplained.
 *
 * @since 2.2.12
 */
final class EcsLoggingSliceReasonTest {

    private static final String CAP = "EcsLoggingSliceReasonCap";

    private static final String LOGGER = "http.access";

    private Capture capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearAll();
        this.capture = new Capture(CAP);
        this.capture.start();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getRootLogger().addAppender(this.capture, null, null);
        cfg.getLoggerConfig(LOGGER).addAppender(this.capture, null, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.getRootLogger().removeAppender(CAP);
        cfg.getLoggerConfig(LOGGER).removeAppender(CAP);
        this.capture.stop();
        ctx.updateLoggers();
        ThreadContext.clearAll();
    }

    @Test
    void theAccessRecordCarriesTheReasonOfTheAnswer() {
        final Response sent = EcsLoggingSliceReasonTest.serve(
            new AnswerReason(ResponseBuilder.notFound().build()).stamped("negative_cache")
        );
        MatcherAssert.assertThat(
            "the access record says the 404 came from the negative cache",
            this.capture.reason(), new IsEqual<>("negative_cache")
        );
        MatcherAssert.assertThat(
            "the internal header does not reach the client",
            sent.headers().values(AnswerReason.HEADER), new IsEqual<>(List.of())
        );
    }

    @Test
    void anAnswerNobodyExplainedIsNamedByItsStatus() {
        EcsLoggingSliceReasonTest.serve(ResponseBuilder.badGateway().build());
        MatcherAssert.assertThat(this.capture.reason(), new IsEqual<>("bad_gateway"));
    }

    @Test
    void aSuccessfulAnswerHasNoReason() {
        EcsLoggingSliceReasonTest.serve(
            new AnswerReason(ResponseBuilder.ok().build()).stamped("negative_cache")
        );
        MatcherAssert.assertThat(this.capture.reason(), new IsEqual<>(null));
    }

    @Test
    void aSlotSentByAClientIsDropped() {
        final AtomicReference<List<String>> seen = new AtomicReference<>();
        final Slice origin = (line, headers, body) -> {
            seen.set(headers.values(RequestReasons.SLOT_HEADER));
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        new EcsLoggingSlice(origin).response(
            new RequestLine("GET", "/repo/a.jar"),
            Headers.from(RequestReasons.SLOT_HEADER, "forged"), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(seen.get(), new IsEqual<>(List.of()));
    }

    @Test
    void aTraceIdSentByAClientIsDropped() {
        final AtomicReference<List<String>> seen = new AtomicReference<>();
        final Slice origin = (line, headers, body) -> {
            seen.set(headers.values(RequestTrace.HEADER));
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
        new EcsLoggingSlice(origin).response(
            new RequestLine("GET", "/repo/a.jar"),
            Headers.from(RequestTrace.HEADER, "forged"), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(seen.get(), new IsEqual<>(List.of()));
    }

    /**
     * Serve one request through the access-log slice.
     * @param answer Answer of the origin
     * @return What the client gets
     */
    private static Response serve(final Response answer) {
        return new EcsLoggingSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(answer)
        ).response(new RequestLine("GET", "/repo/a.jar"), Headers.EMPTY, Content.EMPTY).join();
    }

    /**
     * Captures access records.
     */
    private static final class Capture extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        Capture(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            synchronized (this.events) {
                this.events.add(event.toImmutable());
            }
        }

        String reason() {
            synchronized (this.events) {
                for (final LogEvent evt : this.events) {
                    if (evt.getMessage() instanceof MapMessage<?, ?> map
                        && map.getData().get("http.response.status_code") != null) {
                        final Object reason = map.getData().get("event.reason");
                        return reason == null ? null : reason.toString();
                    }
                }
            }
            return null;
        }
    }
}
