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
import com.auto1.pantera.http.headers.TrustedProxyFixture;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
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
 * Regression tests for B16 behind a trusted proxy: nginx's
 * {@code $proxy_add_x_forwarded_for} appends to the client's own
 * {@code X-Forwarded-For}, so its leftmost entry stays client-controlled
 * even with {@code trust_forwarded_headers} on. Keying the throttle on it
 * handed a fresh (user, address) budget to every request that rotated the
 * header. The throttle must key on the address the proxy recorded.
 *
 * @since 2.2.9
 */
@ExtendWith(VertxExtension.class)
final class AuthHandlerTrustedProxyThrottleTest {

    /**
     * Client address as the proxy records it (appended / X-Real-IP).
     */
    private static final String PROXY_SEEN = "198.51.100.20";

    private TrustedProxyFixture proxy;

    private HttpServer server;

    private WebClient client;

    @BeforeEach
    void setUp(final Vertx vertx) throws Exception {
        this.proxy = new TrustedProxyFixture();
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        final AuthHandler handler = new AuthHandler(
            new JwtTokens(
                (RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic(), null, null, null
            ),
            (name, pass) -> Optional.<AuthUser>empty(),
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
        this.proxy.close();
    }

    @Test
    void rotatingLeftmostForwardedEntryDoesNotEscapeTheThrottle() throws Exception {
        final MultiMap first = MultiMap.caseInsensitiveMultiMap()
            .add("X-Forwarded-For", "10.0.0.1, " + PROXY_SEEN);
        final MultiMap second = MultiMap.caseInsensitiveMultiMap()
            .add("X-Forwarded-For", "10.0.0.2, " + PROXY_SEEN);
        final MultiMap third = MultiMap.caseInsensitiveMultiMap()
            .add("X-Forwarded-For", "10.0.0.3, " + PROXY_SEEN);
        this.login(first);
        this.login(second);
        MatcherAssert.assertThat(
            this.login(third), new IsEqual<>(429)
        );
    }

    @Test
    void rotatingForwardedForUnderAFixedRealIpDoesNotEscapeTheThrottle() throws Exception {
        this.login(this.withRealIp("10.0.1.1"));
        this.login(this.withRealIp("10.0.1.2"));
        MatcherAssert.assertThat(
            this.login(this.withRealIp("10.0.1.3")), new IsEqual<>(429)
        );
    }

    private MultiMap withRealIp(final String spoofed) {
        return MultiMap.caseInsensitiveMultiMap()
            .add("X-Forwarded-For", spoofed + ", " + PROXY_SEEN)
            .add("X-Real-IP", PROXY_SEEN);
    }

    private int login(final MultiMap headers) throws Exception {
        return this.client.post(this.server.actualPort(), "localhost", "/api/v1/auth/token")
            .putHeaders(headers)
            .sendJsonObject(new JsonObject().put("name", "victim").put("pass", "wrong"))
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS)
            .statusCode();
    }
}
