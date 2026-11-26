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
import com.auto1.pantera.auth.OktaAuthContext;
import com.auto1.pantera.security.policy.Policy;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The sign-in endpoint tells the sign-in chain that a person is signing
 * in, so the Okta provider may start a push or verify a typed code. A
 * repository request never does (see {@code OktaSecondFactorTest}).
 *
 * @since 2.2.15
 */
@ExtendWith(VertxExtension.class)
final class AuthHandlerInteractiveSignInTest {

    /**
     * What the chain saw in the Okta context, one entry per login:
     * "interactive/code".
     */
    private final List<String> seen = new CopyOnWriteArrayList<>();

    /**
     * API server.
     */
    private HttpServer server;

    /**
     * API client.
     */
    private WebClient client;

    @BeforeEach
    void setUp(final Vertx vertx) throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair pair = gen.generateKeyPair();
        final AuthHandler handler = new AuthHandler(
            new JwtTokens(
                (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic(),
                null, null, null
            ),
            (name, pass) -> {
                this.seen.add(OktaAuthContext.interactive() + "/" + OktaAuthContext.mfaCode());
                return Optional.empty();
            },
            null, Policy.FREE, null, null, null,
            new LoginThrottle(100, Duration.ofMinutes(15), System::nanoTime)
        );
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        handler.register(router);
        this.server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        this.client = WebClient.create(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void signInWithoutCodeIsInteractive() throws Exception {
        this.signIn(new JsonObject().put("name", "alice").put("pass", "secret"));
        MatcherAssert.assertThat(
            this.seen, new IsEqual<>(List.of("true/null"))
        );
    }

    @Test
    void signInWithCodeIsInteractiveAndCarriesTheCode() throws Exception {
        this.signIn(
            new JsonObject().put("name", "alice").put("pass", "secret")
                .put("mfa_code", "123456")
        );
        MatcherAssert.assertThat(
            this.seen, new IsEqual<>(List.of("true/123456"))
        );
    }

    @Test
    void codeOfOneSignInDoesNotReachTheNext() throws Exception {
        for (int idx = 0; idx < 8; idx += 1) {
            this.signIn(
                new JsonObject().put("name", "alice").put("pass", "secret")
                    .put("mfa_code", "123456")
            );
            this.signIn(new JsonObject().put("name", "bob").put("pass", "secret"));
        }
        MatcherAssert.assertThat(
            "a sign-in without a code never sees the code of an earlier one",
            this.seen.stream().filter("true/null"::equals).count(), new IsEqual<>(8L)
        );
    }

    private void signIn(final JsonObject body) throws Exception {
        this.client.post(this.server.actualPort(), "127.0.0.1", "/api/v1/auth/token")
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
