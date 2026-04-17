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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.test.TestPanteraCaches;
import com.auto1.pantera.test.TestSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test base for AsyncApiVerticle integration tests.
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
public class AsyncApiTestBase {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    private static HikariDataSource sharedDs;

    /**
     * Test timeout in seconds.
     */
    static final long TEST_TIMEOUT = Duration.ofSeconds(5).toSeconds();

    /**
     * Service host.
     */
    static final String HOST = "localhost";

    /**
     * RS256 test token, generated dynamically in setUp.
     */
    static String TEST_TOKEN;

    /**
     * Server port.
     */
    private int port;

    @BeforeAll
    static void initDb() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        sharedDs = new HikariDataSource(cfg);
        DbManager.migrate(sharedDs);
    }

    @AfterAll
    static void closeDb() {
        if (sharedDs != null) {
            sharedDs.close();
        }
    }

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
        // Generate RS256 key pair for tests
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        final RSAPrivateKey privateKey = (RSAPrivateKey) kp.getPrivate();
        final RSAPublicKey publicKey = (RSAPublicKey) kp.getPublic();
        final Algorithm algorithm = Algorithm.RSA256(publicKey, privateKey);
        // Generate a valid RS256 test token
        TEST_TOKEN = JWT.create()
            .withSubject("pantera")
            .withClaim("context", "test")
            .withClaim("type", "access")
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(algorithm);
        // JwtTokens needs RS256 keys — no DB, no settings, no blocklist for tests
        final JwtTokens jwtTokens = new JwtTokens(
            privateKey, publicKey, null, null, null
        );
        // AsyncApiVerticle still takes a JWTAuth parameter for backward compat,
        // but since 2.1.0 it's dead code (RS256 path via jwtTokens is mandatory).
        // Production passes null here — mirror that to stay honest.
        final AsyncApiVerticle verticle = new AsyncApiVerticle(
            new TestPanteraCaches(),
            storage,
            0,
            security,
            Optional.empty(),
            null,
            Optional.empty(),
            NoopCooldownService.INSTANCE,
            new TestSettings(),
            ArtifactIndex.NOP,
            sharedDs,
            jwtTokens
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
