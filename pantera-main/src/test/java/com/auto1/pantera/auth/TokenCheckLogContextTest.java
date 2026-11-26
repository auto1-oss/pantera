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
package com.auto1.pantera.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The record of a rejected token names the token's owner and the request
 * that carried it, whatever context the validating thread still holds from
 * an earlier request.
 *
 * @since 2.2.15
 */
final class TokenCheckLogContextTest {

    /**
     * Appender name.
     */
    private static final String CAP = "TokenCheckLogContextCap";

    /**
     * Logger of the sign-in code.
     */
    private static final String AUTH_LOG = "com.auto1.pantera.auth";

    /**
     * Captured records.
     */
    private Capture capture;

    /**
     * Handler that rejects every token because its owner is disabled.
     */
    private UnifiedJwtAuthHandler handler;

    /**
     * A valid token of alice.
     */
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair pair = gen.generateKeyPair();
        this.handler = new UnifiedJwtAuthHandler(
            (RSAPublicKey) pair.getPublic(), null, null, name -> false
        );
        this.token = JWT.create()
            .withSubject("alice")
            .withClaim("context", "test")
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000001")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(
                Algorithm.RSA256(
                    (RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate()
                )
            );
        this.capture = new Capture(TokenCheckLogContextTest.CAP);
        this.capture.start();
        Configurator.setLevel(TokenCheckLogContextTest.AUTH_LOG, Level.WARN);
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration cfg = ctx.getConfiguration();
        cfg.addAppender(this.capture);
        cfg.getLoggerConfig(TokenCheckLogContextTest.AUTH_LOG)
            .addAppender(this.capture, null, null);
        ctx.updateLoggers();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getLoggerConfig(TokenCheckLogContextTest.AUTH_LOG)
            .removeAppender(TokenCheckLogContextTest.CAP);
        this.capture.stop();
        ctx.updateLoggers();
        MDC.clear();
    }

    @Test
    void rejectedTokenIsRecordedUnderItsOwner() {
        MDC.put("user.name", "mallory");
        this.handler.validated(this.token);
        MatcherAssert.assertThat(
            "the record names the owner of the token, not the user an earlier"
                + " request left on the thread",
            this.capture.users(), new IsEqual<>(List.of("alice"))
        );
    }

    @Test
    void threadKeepsItsContextAfterTheCheck() {
        MDC.put("user.name", "mallory");
        MDC.put("trace.id", "trace-of-mallory");
        final Map<String, String> before = MDC.getCopyOfContextMap();
        this.handler.validated(this.token);
        MatcherAssert.assertThat(
            MDC.getCopyOfContextMap(), new IsEqual<>(before)
        );
    }

    @Test
    void asyncCheckIsRecordedUnderTheTraceOfItsRequest() {
        MDC.put("trace.id", "trace-of-alice");
        MDC.put("client.ip", "192.0.2.7");
        this.handler.user(this.token).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the validating thread carries the caller's trace and address",
            this.capture.traces(), new IsEqual<>(List.of("trace-of-alice/192.0.2.7"))
        );
        MatcherAssert.assertThat(
            "and the record names the owner of the token",
            this.capture.users(), new IsEqual<>(List.of("alice"))
        );
    }

    /**
     * Captures the records of the sign-in logger as the JSON layout would
     * write them: a key the thread's context holds wins over the field of
     * the same name.
     */
    private static final class Capture extends AbstractAppender {

        /**
         * Effective user name of each record.
         */
        private final List<String> names = new CopyOnWriteArrayList<>();

        /**
         * "trace.id/client.ip" of each record.
         */
        private final List<String> origins = new CopyOnWriteArrayList<>();

        Capture(final String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            if (!TokenCheckLogContextTest.AUTH_LOG.equals(event.getLoggerName())) {
                return;
            }
            final Object bound = event.getContextData().getValue("user.name");
            if (bound == null && event.getMessage() instanceof MapMessage<?, ?> map) {
                this.names.add(String.valueOf(map.get("user.name")));
            } else {
                this.names.add(String.valueOf(bound));
            }
            this.origins.add(
                event.getContextData().getValue("trace.id") + "/"
                    + event.getContextData().getValue("client.ip")
            );
        }

        List<String> users() {
            return List.copyOf(this.names);
        }

        List<String> traces() {
            return List.copyOf(this.origins);
        }
    }
}
