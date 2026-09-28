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

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.auth.LoginThrottle;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
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
 * Regression tests for B103: the API-token mint restriction was a
 * suffix match on the raw request path in the {@code /api/v1/*} filter,
 * while Vert.x-web routes tolerate a trailing slash and collapse
 * {@code //}. An API token posted to {@code /auth/token/generate/} or
 * {@code /auth//token/generate} slipped past the check and minted a new
 * (possibly permanent) API token. The endpoint must enforce the rule on
 * the verified token type itself.
 *
 * @since 2.2.9
 */
@ExtendWith(VertxExtension.class)
final class AuthHandlerGenerateTokenTypeTest {

    /**
     * Test-only header carrying the token type the stand-in filter stamps
     * on the principal (what the real /api/v1 filter does after verifying
     * the JWT).
     */
    private static final String TYPE = "X-Test-Token-Type";

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
            (name, pass) -> Optional.empty(),
            null, Policy.FREE, null, null, null,
            new LoginThrottle(5, Duration.ofMinutes(15), System::nanoTime)
        );
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route("/api/v1/*").handler(
            ctx -> {
                ctx.setUser(User.fromName("alice"));
                ctx.user().principal().mergeIn(
                    new JsonObject()
                        .put(AuthTokenRest.SUB, "alice")
                        .put(AuthTokenRest.CONTEXT, "local")
                        .put(AuthTokenRest.TYPE, ctx.request().getHeader(TYPE))
                );
                ctx.next();
            }
        );
        handler.registerProtected(router);
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
    void apiTokenCannotMintThroughATrailingSlash() throws Exception {
        MatcherAssert.assertThat(
            this.generate("/api/v1/auth/token/generate/", "api"), new IsEqual<>(401)
        );
    }

    @Test
    void apiTokenCannotMintThroughADoubleSlash() throws Exception {
        MatcherAssert.assertThat(
            this.generate("/api/v1/auth//token/generate", "api"), new IsEqual<>(401)
        );
    }

    @Test
    void refreshTokenCannotMint() throws Exception {
        MatcherAssert.assertThat(
            this.generate("/api/v1/auth/token/generate", "refresh"), new IsEqual<>(401)
        );
    }

    @Test
    void sessionTokenMints() throws Exception {
        MatcherAssert.assertThat(
            this.generate("/api/v1/auth/token/generate/", "access"), new IsEqual<>(200)
        );
    }

    private int generate(final String path, final String type) throws Exception {
        return this.client.post(this.server.actualPort(), "localhost", path)
            .putHeader(AuthHandlerGenerateTokenTypeTest.TYPE, type)
            .sendJsonObject(new JsonObject().put("label", "t").put("expiry_days", 1))
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS)
            .statusCode();
    }
}
