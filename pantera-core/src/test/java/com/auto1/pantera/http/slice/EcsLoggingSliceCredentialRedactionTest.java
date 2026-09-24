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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.Message;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Credentials carried in a request path must never reach the access log or
 * any other log line emitted while that request is on the thread.
 *
 * <p>Regression: {@code npm logout} sends {@code DELETE /-/user/token/<token>}
 * and conda clients use {@code /t/<token>/...}; the access record used to
 * carry the raw token in {@code url.original} / {@code url.path}.</p>
 */
final class EcsLoggingSliceCredentialRedactionTest {

    private static final String CAP = "EcsLoggingSliceRedactionCap";

    private static final String LOGGER = "http.access";

    private static final String JWT =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"
            + ".eyJzdWIiOiJhbGljZSIsInR5cGUiOiJhcGkifQ"
            + ".c2lnbmF0dXJlLXZhbHVlLWhlcmUtX18tLQ";

    private CapturingAppender capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearAll();
        this.capture = new CapturingAppender(CAP);
        this.capture.start();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getRootLogger().addAppender(this.capture, Level.ALL, null);
        cfg.getLoggerConfig(LOGGER).addAppender(this.capture, Level.ALL, null);
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
    @DisplayName("npm logout token path is redacted in every emitted record")
    void npmLogoutTokenNeverLogged() {
        this.send("DELETE", "/npm_group/-/user/token/" + JWT, 404);
        MatcherAssert.assertThat(
            "some record must have been captured",
            this.capture.count() > 0, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no record may contain the token",
            this.capture.anyContains(JWT), new IsEqual<>(false)
        );
    }

    @Test
    @DisplayName("conda /t/<token>/ path is redacted in every emitted record")
    void condaPathTokenNeverLogged() {
        this.send("GET", "/conda/t/" + JWT + "/noarch/repodata.json", 500);
        MatcherAssert.assertThat(
            "some record must have been captured",
            this.capture.count() > 0, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no record may contain the token",
            this.capture.anyContains(JWT), new IsEqual<>(false)
        );
    }

    @Test
    @DisplayName("EcsLogger redacts url.path and message supplied by any call site")
    void ecsLoggerRedactsUrlFields() {
        EcsLogger.warn("com.auto1.pantera.test")
            .message("Rejecting /npm/-/user/token/" + JWT)
            .field("url.path", "/npm/-/user/token/" + JWT)
            .field("url.original", "https://u:p4ss@host/conda/t/" + JWT + "/x")
            .log();
        MatcherAssert.assertThat(
            "some record must have been captured",
            this.capture.count() > 0, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the token must be redacted",
            this.capture.anyContains(JWT), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "userinfo password must be redacted",
            this.capture.anyContains("p4ss"), new IsEqual<>(false)
        );
    }

    @Test
    @DisplayName("JWT payload+signature split off by conda after the repo name is never logged")
    void condaSplitJwtFragmentNeverLogged() {
        final String fragment = JWT.substring(JWT.indexOf('.'));
        final String header = JWT.substring(0, JWT.indexOf('.'));
        this.send(
            "GET",
            "/t/" + header + "/test_prefix/api/conda" + fragment + "/noarch/repodata.json",
            404
        );
        EcsLogger.info("com.auto1.pantera.settings")
            .message("Repository not found in configuration")
            .field("repository.name", "conda" + fragment)
            .log();
        MatcherAssert.assertThat(
            "some record must have been captured",
            this.capture.count() > 0, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no record may contain the JWT payload",
            this.capture.anyContains(JWT.split("\\.")[1]), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "no record may contain the JWT signature",
            this.capture.anyContains(JWT.split("\\.")[2]), new IsEqual<>(false)
        );
    }

    private void send(final String method, final String path, final int status) {
        final Slice origin = (line, headers, body) -> {
            EcsLogger.error("com.auto1.pantera.test")
                .message("failed " + line.uri().getPath())
                .field("url.path", line.uri().getPath())
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.byCode(status)).build()
            );
        };
        new EcsLoggingSlice(origin).response(
            new RequestLine(method, path), Headers.EMPTY, Content.EMPTY
        ).handle((resp, err) -> null).join();
    }

    /**
     * Collects log events so the test can assert on them.
     */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        CapturingAppender(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            synchronized (this.events) {
                this.events.add(event.toImmutable());
            }
        }

        int count() {
            synchronized (this.events) {
                return this.events.size();
            }
        }

        boolean anyContains(final String needle) {
            synchronized (this.events) {
                for (final LogEvent evt : this.events) {
                    if (render(evt).contains(needle)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String render(final LogEvent evt) {
            final StringBuilder out = new StringBuilder();
            out.append(evt.getContextData().toMap());
            final Message msg = evt.getMessage();
            if (msg instanceof MapMessage<?, ?> map) {
                out.append(map.getData());
            }
            out.append(msg.getFormattedMessage());
            return out.toString();
        }
    }
}
