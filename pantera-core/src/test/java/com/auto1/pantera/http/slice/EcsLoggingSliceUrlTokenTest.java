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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.ArrayList;
import java.util.List;
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
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The access log masks a credential carried as a {@code /t/<token>/} path
 * segment (conda channel tokens, anaconda upload tickets) in
 * {@code url.original} and {@code url.path}.
 *
 * @since 2.2.9
 */
final class EcsLoggingSliceUrlTokenTest {

    private static final String CAP = "EcsLoggingSliceUrlTokenCap";

    private static final String LOGGER = "http.access";

    /**
     * Token-looking path segment.
     */
    private static final String TOKEN = "65794a68624763694f694a53557a49314e694a39";

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
    void accessLogMasksUrlToken() {
        new EcsLoggingSlice(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture()
        ).response(
            new RequestLine(
                "GET",
                String.format(
                    "/t/%s/test_prefix/api/my-conda/noarch/repodata.json",
                    EcsLoggingSliceUrlTokenTest.TOKEN
                )
            ),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        final String logged = this.capture.urls();
        MatcherAssert.assertThat(
            "the access record carries the request URL",
            logged, new StringContains("/noarch/repodata.json")
        );
        MatcherAssert.assertThat(
            "the token is not written to the log",
            logged.contains(EcsLoggingSliceUrlTokenTest.TOKEN), new IsEqual<>(false)
        );
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

        /**
         * URL fields of the access record, from its message and its context.
         * @return Concatenated values
         */
        String urls() {
            final StringBuilder res = new StringBuilder();
            synchronized (this.events) {
                for (final LogEvent evt : this.events) {
                    if (evt.getMessage() instanceof MapMessage<?, ?> map
                        && map.getData().get("http.response.status_code") != null) {
                        for (final String key : List.of("url.original", "url.path")) {
                            res.append(map.getData().get(key)).append(' ')
                                .append(evt.getContextData().<Object>getValue(key)).append(' ');
                        }
                    }
                }
            }
            return res.toString();
        }
    }
}
