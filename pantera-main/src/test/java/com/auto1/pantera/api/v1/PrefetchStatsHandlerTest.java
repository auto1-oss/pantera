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
import com.auto1.pantera.prefetch.PrefetchCoordinator;
import com.auto1.pantera.prefetch.PrefetchMetrics;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.test.TestPanteraCaches;
import com.auto1.pantera.test.TestSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link PrefetchStatsHandler}.
 *
 * <p>Wires a fresh {@link PrefetchMetrics} into a freshly-deployed
 * {@link AsyncApiVerticle} per test, populates the metrics with a
 * known set of events, and verifies the JSON shape and counts the
 * handler returns.</p>
 *
 * @since 2.2.0
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
final class PrefetchStatsHandlerTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    private static final String HOST = "localhost";

    private static final long TEST_TIMEOUT = Duration.ofSeconds(10).toSeconds();

    private static HikariDataSource sharedDs;

    private String token;

    private int port;

    private PrefetchMetrics metrics;

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
    void setUp(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.metrics = new PrefetchMetrics(Clock.systemUTC());
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
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        final RSAPrivateKey privateKey = (RSAPrivateKey) kp.getPrivate();
        final RSAPublicKey publicKey = (RSAPublicKey) kp.getPublic();
        final Algorithm algorithm = Algorithm.RSA256(publicKey, privateKey);
        this.token = JWT.create()
            .withSubject("pantera")
            .withClaim("context", "test")
            .withClaim("type", "access")
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(algorithm);
        final JwtTokens jwtTokens = new JwtTokens(
            privateKey, publicKey, null, null, null
        );
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
            jwtTokens,
            this.metrics
        );
        vertx.deployVerticle(verticle, ctx.succeedingThenComplete());
        final long deadline = System.currentTimeMillis()
            + Duration.ofMinutes(1).toMillis();
        while (verticle.actualPort() < 0 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        if (verticle.actualPort() < 0) {
            Assertions.fail("AsyncApiVerticle did not start listening within timeout");
        }
        this.port = verticle.actualPort();
    }

    @Test
    void getStatsReturnsCountsForRepo(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        // Three successful fetches, one cooldown-blocked, two queue-full drops,
        // one circuit-open drop. Sums across ecosystems are read by the
        // handler's per-(repo,outcome) accessor.
        this.metrics.completed("maven_proxy", "maven", PrefetchMetrics.OUTCOME_FETCHED_200);
        this.metrics.completed("maven_proxy", "maven", PrefetchMetrics.OUTCOME_FETCHED_200);
        this.metrics.completed("maven_proxy", "maven", PrefetchMetrics.OUTCOME_FETCHED_200);
        this.metrics.completed(
            "maven_proxy", "maven", PrefetchMetrics.OUTCOME_COOLDOWN_BLOCKED
        );
        this.metrics.dropped("maven_proxy", PrefetchCoordinator.REASON_QUEUE_FULL);
        this.metrics.dropped("maven_proxy", PrefetchCoordinator.REASON_QUEUE_FULL);
        this.metrics.dropped("maven_proxy", PrefetchCoordinator.REASON_CIRCUIT_OPEN);
        final HttpResponse<Buffer> res = WebClient.create(vertx)
            .get(this.port, HOST, "/api/v1/repositories/maven_proxy/prefetch/stats")
            .bearerTokenAuthentication(this.token)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(res.statusCode(), Matchers.is(200));
        final var body = res.bodyAsJsonObject();
        MatcherAssert.assertThat(body.getString("repo"), Matchers.is("maven_proxy"));
        MatcherAssert.assertThat(body.getString("window"), Matchers.is("24h"));
        MatcherAssert.assertThat(body.getLong("prefetched"), Matchers.is(3L));
        MatcherAssert.assertThat(body.getLong("cooldown_blocked"), Matchers.is(1L));
        MatcherAssert.assertThat(body.getLong("dropped_queue_full"), Matchers.is(2L));
        MatcherAssert.assertThat(
            body.getLong("dropped_semaphore_saturated"), Matchers.is(0L)
        );
        MatcherAssert.assertThat(body.getLong("dropped_dedup_in_flight"), Matchers.is(0L));
        MatcherAssert.assertThat(body.getLong("dropped_circuit_open"), Matchers.is(1L));
        MatcherAssert.assertThat(
            "last_fetch_at must be present after a successful fetch",
            body.getString("last_fetch_at"), Matchers.notNullValue()
        );
        ctx.completeNow();
    }

    @Test
    void getStatsUnknownRepoReturnsZeroCounts(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        // No events recorded for "no_such_repo" — handler must respond 200
        // with all-zero counters (NOT 404), so the UI degrades gracefully
        // for repos that have never received a prefetch attempt.
        final HttpResponse<Buffer> res = WebClient.create(vertx)
            .get(this.port, HOST, "/api/v1/repositories/no_such_repo/prefetch/stats")
            .bearerTokenAuthentication(this.token)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(res.statusCode(), Matchers.is(200));
        final var body = res.bodyAsJsonObject();
        MatcherAssert.assertThat(body.getString("repo"), Matchers.is("no_such_repo"));
        MatcherAssert.assertThat(body.getLong("prefetched"), Matchers.is(0L));
        MatcherAssert.assertThat(body.getLong("cooldown_blocked"), Matchers.is(0L));
        MatcherAssert.assertThat(body.getLong("dropped_queue_full"), Matchers.is(0L));
        MatcherAssert.assertThat(
            body.getLong("dropped_semaphore_saturated"), Matchers.is(0L)
        );
        MatcherAssert.assertThat(body.getLong("dropped_dedup_in_flight"), Matchers.is(0L));
        MatcherAssert.assertThat(body.getLong("dropped_circuit_open"), Matchers.is(0L));
        MatcherAssert.assertThat(
            "last_fetch_at must be omitted when no successful fetch recorded",
            body.containsKey("last_fetch_at"), Matchers.is(false)
        );
        ctx.completeNow();
    }

    @Test
    void getStatsLastFetchAtReflectsLatestSuccess(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        // Pin a fake clock so we can compare the JSON timestamp byte-for-byte.
        final Instant frozen = Instant.parse("2026-05-04T11:42:13Z");
        final PrefetchMetrics fixed = new PrefetchMetrics(
            Clock.fixed(frozen, ZoneOffset.UTC)
        );
        // Re-deploy a verticle wired to the fixed-clock metrics so the
        // per-test setUp's system-clock metrics doesn't mask the assertion.
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
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        final RSAPrivateKey priv = (RSAPrivateKey) kp.getPrivate();
        final RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        final Algorithm alg = Algorithm.RSA256(pub, priv);
        final String tk = JWT.create()
            .withSubject("pantera")
            .withClaim("context", "test")
            .withClaim("type", "access")
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(alg);
        final JwtTokens jt = new JwtTokens(priv, pub, null, null, null);
        // Record the fetch BEFORE deploying so the AtomicLong is set when the
        // first request arrives.
        fixed.completed("npm_proxy", "npm", PrefetchMetrics.OUTCOME_FETCHED_200);
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
            jt,
            fixed
        );
        vertx.deployVerticle(verticle, ctx.succeedingThenComplete());
        final long deadline = System.currentTimeMillis()
            + Duration.ofMinutes(1).toMillis();
        while (verticle.actualPort() < 0 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        Assertions.assertTrue(verticle.actualPort() > 0, "verticle did not start");
        final HttpResponse<Buffer> res = WebClient.create(vertx)
            .get(verticle.actualPort(), HOST,
                "/api/v1/repositories/npm_proxy/prefetch/stats")
            .bearerTokenAuthentication(tk)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat(res.statusCode(), Matchers.is(200));
        final var body = res.bodyAsJsonObject();
        MatcherAssert.assertThat(body.getLong("prefetched"), Matchers.is(1L));
        // Instant.toString() produces ISO-8601: "2026-05-04T11:42:13Z".
        MatcherAssert.assertThat(
            body.getString("last_fetch_at"), Matchers.is("2026-05-04T11:42:13Z")
        );
        ctx.completeNow();
    }
}
