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
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.auth.BearerAuthScheme;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import java.security.AllPermission;
import java.security.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
 * Regression tests for B55: the access log's {@code user.name} was taken
 * from the raw {@code Authorization} header before authentication. Bearer
 * requests (every token client) were logged as {@code anonymous}, and a
 * failed Basic login was logged under the unverified claimed username.
 * The access log must record the principal the auth layer verified.
 *
 * @since 2.2.9
 */
final class EcsLoggingSliceUserTest {

    private static final String CAP = "EcsLoggingSliceUserCap";

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
    void bearerAuthenticatedRequestIsLoggedUnderTheVerifiedUser() {
        final Response resp = new EcsLoggingSlice(
            this.authz(Optional.of(new AuthUser("alice", "test")))
        ).response(
            new RequestLine("GET", "/repo/a.jar"),
            Headers.from(new Authorization.Bearer("opaque-token")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the access log must carry the verified Bearer principal",
            this.capture.user(), new IsEqual<>("alice")
        );
        MatcherAssert.assertThat(
            "the internal principal header must not reach the client",
            resp.headers().values(EcsLoggingSlice.AUTHENTICATED_USER_HEADER).isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void failedBasicLoginIsNotLoggedUnderTheClaimedName() {
        new EcsLoggingSlice(this.authz(Optional.empty())).response(
            new RequestLine("GET", "/repo/a.jar"),
            Headers.from(new Authorization.Basic("admin", "wrong")),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(this.capture.user(), new IsEqual<>("anonymous"));
    }

    @Test
    void spoofedPrincipalHeaderFromTheOriginIsIgnored() {
        final Slice origin = (line, headers, body) -> ResponseBuilder.ok()
            .header(EcsLoggingSlice.AUTHENTICATED_USER_HEADER, "mallory")
            .completedFuture();
        new EcsLoggingSlice(
            new AuthzSlice(
                origin,
                new AuthScheme.Fake(Optional.of(AuthUser.ANONYMOUS), ""),
                new OperationControl(
                    user -> {
                        final Permissions all = new Permissions();
                        all.add(new AllPermission());
                        return all;
                    },
                    new AdapterBasicPermission("repo", "read")
                )
            )
        ).response(new RequestLine("GET", "/repo/a.jar"), Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(this.capture.user(), new IsEqual<>("anonymous"));
    }

    private Slice authz(final Optional<AuthUser> user) {
        return new AuthzSlice(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(),
            new BearerAuthScheme(
                tkn -> CompletableFuture.completedFuture(user), "realm=\"pantera\""
            ),
            new OperationControl(
                usr -> {
                    final Permissions all = new Permissions();
                    all.add(new AllPermission());
                    return all;
                },
                new AdapterBasicPermission("repo", "read")
            )
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

        String user() {
            synchronized (this.events) {
                for (final LogEvent evt : this.events) {
                    if (evt.getMessage() instanceof MapMessage<?, ?> map
                        && map.getData().get("http.response.status_code") != null) {
                        return evt.getContextData().getValue("user.name");
                    }
                }
            }
            return null;
        }
    }
}
