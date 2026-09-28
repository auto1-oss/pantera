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
import com.auto1.pantera.http.auth.AuthUser;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression tests for B16: the login throttle trusted a client-supplied
 * {@code X-Forwarded-For} unconditionally, so rotating it gave unlimited
 * password guesses and the right password with a spoofed header bypassed a
 * lockout. Without a declared trusted proxy the TCP peer is the client.
 *
 * @since 2.2.9
 */
@ExtendWith(VertxExtension.class)
final class AuthHandlerLoginThrottleTest {

    /**
     * The only password the fake auth chain accepts.
     */
    private static final String RIGHT = "right-password";

    private HttpServer server;

    private WebClient client;

    @BeforeEach
    void setUp(final Vertx vertx) throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        final AuthHandler handler = new AuthHandler(
            new JwtTokens(
                (RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic(), null, null, null
            ),
            (name, pass) -> AuthHandlerLoginThrottleTest.RIGHT.equals(pass)
                ? Optional.of(new AuthUser(name, "local")) : Optional.empty(),
            null, Policy.FREE, null, null, null,
            new LoginThrottle(2, Duration.ofMinutes(15), System::nanoTime)
        );
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        handler.register(router);
        this.server = vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        this.client = WebClient.create(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void rotatingXForwardedForDoesNotEscapeTheThrottle() throws Exception {
        this.login("wrong", "10.0.0.1");
        this.login("wrong", "10.0.0.2");
        MatcherAssert.assertThat(
            "a third guess with yet another spoofed address must be throttled",
            this.login("wrong", "10.0.0.3").statusCode(), new IsEqual<>(429)
        );
        final HttpResponse<Buffer> right = this.login(AuthHandlerLoginThrottleTest.RIGHT, "10.9.9.9");
        MatcherAssert.assertThat(
            "the right password with a spoofed address must not bypass the lockout",
            right.statusCode(), new IsEqual<>(429)
        );
        MatcherAssert.assertThat(
            "Retry-After is derived from the configured window",
            Integer.parseInt(right.getHeader("Retry-After")) > 800, new IsEqual<>(true)
        );
    }

    private HttpResponse<Buffer> login(final String pass, final String forwarded)
        throws Exception {
        return this.client.post(this.server.actualPort(), "localhost", "/api/v1/auth/token")
            .putHeader("X-Forwarded-For", forwarded)
            .sendJsonObject(new JsonObject().put("name", "victim").put("pass", pass))
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
