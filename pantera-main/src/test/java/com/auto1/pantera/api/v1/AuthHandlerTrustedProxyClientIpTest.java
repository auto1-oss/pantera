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
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.LoginSource;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * even with {@code trust_forwarded_headers} on, and an ALB passes a
 * client-sent {@code X-Real-IP} through unchanged. The address a sign-in
 * is recorded under (failed logins, the sign-in failures report) must be
 * the one the proxy recorded: the rightmost {@code X-Forwarded-For} entry.
 *
 * @since 2.2.9
 */
@ExtendWith(VertxExtension.class)
final class AuthHandlerTrustedProxyClientIpTest {

    /**
     * Client address as the proxy records it (appended / X-Real-IP).
     */
    private static final String PROXY_SEEN = "198.51.100.20";

    /**
     * Where the sign-in chain saw the last login come from.
     */
    private final AtomicReference<LoginSource> source = new AtomicReference<>();

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
            (name, pass) -> {
                this.source.set(LoginSource.current());
                return Optional.<AuthUser>empty();
            },
            null, Policy.FREE, null, null, null
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
    void leftmostForwardedEntryIsNotTheClient() throws Exception {
        MatcherAssert.assertThat(
            this.recorded(
                MultiMap.caseInsensitiveMultiMap()
                    .add("X-Forwarded-For", "10.0.0.1, " + PROXY_SEEN)
            ),
            new IsEqual<>(PROXY_SEEN)
        );
    }

    @Test
    void forwardedForUnderAFixedRealIpIsNotTheClient() throws Exception {
        MatcherAssert.assertThat(
            this.recorded(
                MultiMap.caseInsensitiveMultiMap()
                    .add("X-Forwarded-For", "10.0.1.1, " + PROXY_SEEN)
                    .add("X-Real-IP", PROXY_SEEN)
            ),
            new IsEqual<>(PROXY_SEEN)
        );
    }

    @Test
    void passedThroughRealIpIsNotTheClient() throws Exception {
        // An ALB appends the peer to X-Forwarded-For but passes a
        // client-sent X-Real-IP through untouched.
        MatcherAssert.assertThat(
            this.recorded(
                MultiMap.caseInsensitiveMultiMap()
                    .add("X-Forwarded-For", PROXY_SEEN)
                    .add("X-Real-IP", "10.0.2.1")
            ),
            new IsEqual<>(PROXY_SEEN)
        );
    }

    private String recorded(final MultiMap headers) throws Exception {
        this.client.post(this.server.actualPort(), "localhost", "/api/v1/auth/token")
            .putHeaders(headers)
            .sendJsonObject(new JsonObject().put("name", "victim").put("pass", "wrong"))
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        return this.source.get().clientIp();
    }
}
