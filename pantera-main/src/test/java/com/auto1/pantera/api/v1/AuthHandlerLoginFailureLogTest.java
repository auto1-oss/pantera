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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.auth.LoginThrottle;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
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
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A sign-in that fails with an exception answers the fixed client message
 * and logs the exception once (it used to be dropped entirely).
 *
 * @since 2.2.11
 */
@ExtendWith(VertxExtension.class)
final class AuthHandlerLoginFailureLogTest {

    /**
     * Exception text that must never reach the client.
     */
    private static final String SECRET = "jdbc:postgresql://db.internal.corp:5432 refused";

    private HttpServer server;

    private WebClient client;

    private Capture capture;

    @BeforeEach
    void setUp(final Vertx vertx) throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        final AuthHandler handler = new AuthHandler(
            new JwtTokens(
                (RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic(), null, null, null
            ),
            (name, pass) -> {
                throw new IllegalStateException(SECRET);
            },
            null, Policy.FREE, null, null, null,
            new LoginThrottle(100, Duration.ofMinutes(15), System::nanoTime)
        );
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        handler.register(router);
        this.server = vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        this.client = WebClient.create(vertx);
        this.capture = Capture.of("com.auto1.pantera.api");
    }

    @AfterEach
    void tearDown() throws Exception {
        this.capture.close();
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void failedSignInLogsCauseAndHidesIt() throws Exception {
        final HttpResponse<Buffer> rsp = this.client
            .post(this.server.actualPort(), "localhost", "/api/v1/auth/token")
            .sendJsonObject(new JsonObject().put("name", "alice").put("pass", "pw"))
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat("500", rsp.statusCode(), new IsEqual<>(500));
        MatcherAssert.assertThat(
            "fixed client message",
            rsp.bodyAsJsonObject().getString("message"),
            new IsEqual<>("Sign-in is temporarily unavailable. Please try again.")
        );
        MatcherAssert.assertThat(
            "no exception text in the body",
            rsp.bodyAsString(), new IsNot<>(new StringContains("db.internal.corp"))
        );
        MatcherAssert.assertThat("one cause record", this.capture.events.size(), new IsEqual<>(1));
        final Map<String, Object> rec = this.capture.events.get(0);
        MatcherAssert.assertThat(
            "error.type", rec.get("error.type"),
            new IsEqual<>("java.lang.IllegalStateException")
        );
        MatcherAssert.assertThat(
            "status", rec.get("http.response.status_code"), new IsEqual<>(500)
        );
        MatcherAssert.assertThat(
            "action", rec.get("event.action"), new IsEqual<>("api_request")
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
         * Ctor.
         * @param logger Logger
         */
        private Capture(final Logger logger) {
            super("AuthHandlerLoginFailureLogTest", null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
        }

        static Capture of(final String name) {
            final Capture capture = new Capture((Logger) LogManager.getLogger(name));
            capture.start();
            capture.logger.addAppender(capture);
            capture.logger.setLevel(Level.DEBUG);
            return capture;
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
