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

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.NoopCooldownService;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.test.TestPanteraCaches;
import com.auto1.pantera.test.TestSettings;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Test base for AsyncApiVerticle integration tests.
 */
@ExtendWith(VertxExtension.class)
public class AsyncApiTestBase {

    /**
     * Test timeout in seconds.
     */
    static final long TEST_TIMEOUT = Duration.ofSeconds(5).toSeconds();

    /**
     * Service host.
     */
    static final String HOST = "localhost";

    /**
     * Hardcoded JWT token for test user "pantera" with context "test".
     * Issued with HS256, secret "some secret", no expiry.
     */
    static final String TEST_TOKEN =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9"
        + ".eyJzdWIiOiJwYW50ZXJhIiwiY29udGV4dCI6InRlc3QiLCJpYXQiOjE2ODIwODgxNTh9"
        + ".wWJgLLe4B_zjmhzKGstCFyHX-C2tE6ucu3WC4G5lEdk";

    /**
     * Server port.
     */
    private int port;

    @BeforeEach
    final void setUp(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final Storage storage = new InMemoryStorage();
        final PanteraSecurity security = new PanteraSecurity() {
            @Override
            public Authentication authentication() {
                return (name, pswd) -> Optional.of(new AuthUser("pantera", "test"));
            }

            @Override
            public Policy<?> policy() {
                return Policy.FREE;
            }

            @Override
            public Optional<Storage> policyStorage() {
                return Optional.of(storage);
            }
        };
        final JWTAuth jwt = JWTAuth.create(
            vertx, new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions().setAlgorithm("HS256").setBuffer("some secret")
            )
        );
        final AsyncApiVerticle verticle = new AsyncApiVerticle(
            new TestPanteraCaches(),
            storage,
            0,
            security,
            Optional.empty(),
            jwt,
            Optional.empty(),
            NoopCooldownService.INSTANCE,
            new TestSettings(),
            ArtifactIndex.NOP,
            null
        );
        vertx.deployVerticle(verticle, ctx.succeedingThenComplete());
        this.waitForActualPort(verticle);
        this.port = verticle.actualPort();
    }

    /**
     * Get test server port.
     * @return The port int value
     */
    final int port() {
        return this.port;
    }

    /**
     * Perform HTTP request with test token.
     * @param vertx Vertx instance
     * @param ctx Test context
     * @param method HTTP method
     * @param path Request path
     * @param assertion Response assertion
     * @throws Exception On error
     */
    final void request(final Vertx vertx, final VertxTestContext ctx,
        final HttpMethod method, final String path,
        final Consumer<HttpResponse<Buffer>> assertion) throws Exception {
        this.request(vertx, ctx, method, path, null, assertion);
    }

    /**
     * Perform HTTP request with test token and body.
     * @param vertx Vertx instance
     * @param ctx Test context
     * @param method HTTP method
     * @param path Request path
     * @param body Request body (nullable)
     * @param assertion Response assertion
     * @throws Exception On error
     */
    final void request(final Vertx vertx, final VertxTestContext ctx,
        final HttpMethod method, final String path, final JsonObject body,
        final Consumer<HttpResponse<Buffer>> assertion) throws Exception {
        this.request(vertx, ctx, method, path, body, TEST_TOKEN, assertion);
    }

    /**
     * Perform HTTP request with specified token and body.
     * @param vertx Vertx instance
     * @param ctx Test context
     * @param method HTTP method
     * @param path Request path
     * @param body Request body (nullable)
     * @param token JWT token (nullable for no auth)
     * @param assertion Response assertion
     * @throws Exception On error
     */
    final void request(final Vertx vertx, final VertxTestContext ctx,
        final HttpMethod method, final String path, final JsonObject body,
        final String token,
        final Consumer<HttpResponse<Buffer>> assertion) throws Exception {
        final HttpRequest<Buffer> req = WebClient.create(vertx)
            .request(method, this.port, HOST, path);
        if (token != null) {
            req.bearerTokenAuthentication(token);
        }
        final var future = body != null ? req.sendJsonObject(body) : req.send();
        future.onSuccess(res -> {
                assertion.accept(res);
                ctx.completeNow();
            })
            .onFailure(ctx::failNow)
            .toCompletionStage().toCompletableFuture()
            .get(TEST_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * Waits until the verticle has started listening and the actual port is known.
     * @param verticle The deployed AsyncApiVerticle instance
     */
    private void waitForActualPort(final AsyncApiVerticle verticle) {
        final long deadline = System.currentTimeMillis() + Duration.ofMinutes(1).toMillis();
        while (verticle.actualPort() < 0 && System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (final InterruptedException err) {
                break;
            }
        }
        if (verticle.actualPort() < 0) {
            Assertions.fail("AsyncApiVerticle did not start listening within timeout");
        }
    }

}
