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
import com.auto1.pantera.api.perms.ApiAdminPermission;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.db.dao.SettingsDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.settings.runtime.SettingsKey;
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
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the runtime-tunable settings endpoints exposed by
 * {@link SettingsHandler} (Task 6 of the v2.2.0 perf-pack):
 *
 * <ul>
 *   <li>{@code GET    /api/v1/settings/runtime} — list all 11 keys</li>
 *   <li>{@code GET    /api/v1/settings/runtime/:key} — single key</li>
 *   <li>{@code PATCH  /api/v1/settings/runtime/:key} — admin-only update</li>
 *   <li>{@code DELETE /api/v1/settings/runtime/:key} — admin-only reset</li>
 * </ul>
 *
 * <p>Note: the legacy {@code GET /api/v1/settings} endpoint (which returns
 * the full configured server state — port/version/jwt/etc.) lives in the
 * same handler and is covered by {@link SettingsHandlerTest}. The runtime
 * keys live under {@code /api/v1/settings/runtime} to avoid colliding with
 * the legacy section-based paths ({@code /api/v1/settings/ui},
 * {@code /api/v1/settings/prefixes}, {@code /api/v1/settings/:section}).
 *
 * @since 2.2.0
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
final class SettingsHandlerRuntimeTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    private static final String HOST = "localhost";

    private static final long TEST_TIMEOUT = Duration.ofSeconds(10).toSeconds();

    /**
     * Whether the test user is admin. Mutated per test to flip 200/403.
     */
    private static volatile boolean adminGranted = true;

    private static HikariDataSource sharedDs;

    private String token;

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
    void setUp(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.truncateSettings();
        adminGranted = true;
        final Storage storage = new InMemoryStorage();
        final PanteraSecurity security = new PanteraSecurity() {
            @Override
            public Authentication authentication() {
                return (name, pswd) -> Optional.of(new AuthUser("pantera", "test"));
            }

            @Override
            public Policy<?> policy() {
                return user -> SettingsHandlerRuntimeTest.buildPermissions(
                    SettingsHandlerRuntimeTest.adminGranted
                );
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
            jwtTokens
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

    @AfterEach
    void tearDown() {
        this.truncateSettings();
    }

    @Test
    void getListReturnsAllElevenKeysWithValueDefaultSource(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.request(vertx, ctx, HttpMethod.GET,
            "/api/v1/settings/runtime", null,
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Expected 200, got body: " + res.bodyAsString());
                final JsonObject body = res.bodyAsJsonObject();
                MatcherAssert.assertThat(
                    "List endpoint must return all 11 catalog keys",
                    body.fieldNames(),
                    Matchers.hasSize(SettingsKey.values().length)
                );
                for (final SettingsKey k : SettingsKey.values()) {
                    final JsonObject entry = body.getJsonObject(k.key());
                    MatcherAssert.assertThat(
                        "key " + k.key() + " must be present",
                        entry, Matchers.notNullValue()
                    );
                    MatcherAssert.assertThat(entry.fieldNames(),
                        Matchers.hasItems("value", "default", "source"));
                    MatcherAssert.assertThat(
                        entry.getString("source"),
                        Matchers.is("default")
                    );
                    MatcherAssert.assertThat(
                        entry.getString("default"),
                        Matchers.is(k.defaultRepr())
                    );
                }
                // Lock the value-as-JSON-literal-string contract: protocol
                // is the quoted JSON string "\"h2\"", not the bare string "h2".
                final JsonObject protoEntry =
                    body.getJsonObject("http_client.protocol");
                MatcherAssert.assertThat(protoEntry.getString("value"),
                    Matchers.is("\"h2\""));
                MatcherAssert.assertThat(protoEntry.getString("default"),
                    Matchers.is("\"h2\""));
                MatcherAssert.assertThat(protoEntry.getString("source"),
                    Matchers.is("default"));
                // For an integer key, the value is the JSON literal "4",
                // not the integer 4. (Default raised from 1 → 4 in the
                // v2.2.0 perf bench, 2026-05, to enable real upstream
                // parallelism through the H2 client.)
                final JsonObject poolEntry =
                    body.getJsonObject("http_client.http2_max_pool_size");
                MatcherAssert.assertThat(poolEntry.getString("value"),
                    Matchers.is("4"));
            }
        );
    }

    @Test
    void patchResponseHasGetShapeNotRequestEcho(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        adminGranted = true;
        this.request(vertx, ctx, HttpMethod.PATCH,
            "/api/v1/settings/runtime/http_client.protocol",
            new JsonObject().put("value", "h1"),
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Expected 200, got body: " + res.bodyAsString());
                final JsonObject body = res.bodyAsJsonObject();
                MatcherAssert.assertThat(body.getString("key"),
                    Matchers.is("http_client.protocol"));
                MatcherAssert.assertThat(body.getString("value"),
                    Matchers.is("\"h1\""));
                MatcherAssert.assertThat(body.getString("source"),
                    Matchers.is("db"));
            }
        );
    }

    @Test
    void getKeyReturnsSingleKeyWithValueAndSource(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.request(vertx, ctx, HttpMethod.GET,
            "/api/v1/settings/runtime/http_client.protocol", null,
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                MatcherAssert.assertThat(body.getString("key"),
                    Matchers.is("http_client.protocol"));
                MatcherAssert.assertThat(body.fieldNames(),
                    Matchers.hasItems("value", "source"));
                MatcherAssert.assertThat(body.getString("source"),
                    Matchers.is("default"));
            }
        );
    }

    @Test
    void getKeyForUnknownKeyReturns404(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.request(vertx, ctx, HttpMethod.GET,
            "/api/v1/settings/runtime/no.such.key", null,
            res -> Assertions.assertEquals(404, res.statusCode())
        );
    }

    @Test
    void patchKeyAsAdminWithValidBodyUpdatesRow(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        adminGranted = true;
        this.request(vertx, ctx, HttpMethod.PATCH,
            "/api/v1/settings/runtime/http_client.protocol",
            new JsonObject().put("value", "h1"),
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Expected 200, got body: " + res.bodyAsString());
                // Verify the row was actually written to the DB.
                final SettingsDao dao = new SettingsDao(sharedDs);
                final javax.json.JsonObject saved = dao
                    .get("http_client.protocol")
                    .orElseThrow(
                        () -> new AssertionError("row not persisted")
                    );
                MatcherAssert.assertThat(
                    saved.getString("value"),
                    Matchers.is("h1")
                );
            }
        );
    }

    @Test
    void patchKeyAsNonAdminReturns403(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        adminGranted = false;
        this.request(vertx, ctx, HttpMethod.PATCH,
            "/api/v1/settings/runtime/http_client.protocol",
            new JsonObject().put("value", "h1"),
            res -> Assertions.assertEquals(403, res.statusCode(),
                "non-admin must be rejected; got body: " + res.bodyAsString())
        );
    }

    @Test
    void patchKeyWithUnknownKeyReturns400(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        adminGranted = true;
        this.request(vertx, ctx, HttpMethod.PATCH,
            "/api/v1/settings/runtime/no.such.key",
            new JsonObject().put("value", "x"),
            res -> Assertions.assertEquals(400, res.statusCode())
        );
    }

    @Test
    void patchKeyWithOutOfRangeValueReturns400(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        adminGranted = true;
        this.request(vertx, ctx, HttpMethod.PATCH,
            "/api/v1/settings/runtime/prefetch.concurrency.global",
            new JsonObject().put("value", 99_999),
            res -> {
                Assertions.assertEquals(400, res.statusCode(),
                    "out-of-range value must be rejected; "
                        + "got body: " + res.bodyAsString());
                MatcherAssert.assertThat(
                    res.bodyAsString(),
                    Matchers.containsString("range")
                );
            }
        );
    }

    @Test
    void deleteKeyAsAdminRemovesRowAndSubsequentGetReturnsDefault(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        // Seed a row so DELETE has something to remove.
        final SettingsDao dao = new SettingsDao(sharedDs);
        dao.put(
            "http_client.protocol",
            javax.json.Json.createObjectBuilder().add("value", "h1").build(),
            "test"
        );
        adminGranted = true;
        // First, DELETE.
        final HttpRequest<Buffer> delReq = WebClient.create(vertx)
            .request(HttpMethod.DELETE, this.port, HOST,
                "/api/v1/settings/runtime/http_client.protocol")
            .bearerTokenAuthentication(this.token);
        final HttpResponse<Buffer> delRes = delReq.send()
            .toCompletionStage().toCompletableFuture()
            .get(TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(204, delRes.statusCode(),
            "DELETE must return 204; got body: " + delRes.bodyAsString());
        MatcherAssert.assertThat(
            "row must be physically removed",
            dao.get("http_client.protocol").isPresent(),
            Matchers.is(false)
        );
        // Then, GET — should now reflect the default.
        this.request(vertx, ctx, HttpMethod.GET,
            "/api/v1/settings/runtime/http_client.protocol", null,
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                MatcherAssert.assertThat(body.getString("source"),
                    Matchers.is("default"));
            }
        );
    }

    @Test
    void deleteKeyAsNonAdminReturns403(
        final Vertx vertx, final VertxTestContext ctx) throws Exception {
        adminGranted = false;
        this.request(vertx, ctx, HttpMethod.DELETE,
            "/api/v1/settings/runtime/http_client.protocol", null,
            res -> Assertions.assertEquals(403, res.statusCode())
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Build a {@link PermissionCollection} containing
     * {@link ApiAdminPermission#ADMIN} iff {@code admin} is true.
     */
    private static PermissionCollection buildPermissions(final boolean admin) {
        final Permissions coll = new Permissions();
        if (admin) {
            coll.add(ApiAdminPermission.ADMIN);
        }
        return coll;
    }

    private void request(final Vertx vertx, final VertxTestContext ctx,
        final HttpMethod method, final String path, final JsonObject body,
        final Consumer<HttpResponse<Buffer>> assertion) throws Exception {
        final HttpRequest<Buffer> req = WebClient.create(vertx)
            .request(method, this.port, HOST, path)
            .bearerTokenAuthentication(this.token);
        final var future = body != null
            ? req.sendJsonObject(body)
            : req.send();
        future.onSuccess(res -> {
                assertion.accept(res);
                ctx.completeNow();
            })
            .onFailure(ctx::failNow)
            .toCompletionStage().toCompletableFuture()
            .get(TEST_TIMEOUT, TimeUnit.SECONDS);
    }

    private void truncateSettings() {
        final String sql = "DELETE FROM settings";
        try (Connection conn = sharedDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("truncateSettings failed", err);
        }
    }
}
