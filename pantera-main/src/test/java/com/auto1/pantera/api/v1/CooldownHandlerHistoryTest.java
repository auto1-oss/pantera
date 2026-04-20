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
import com.auto1.pantera.api.perms.ApiCooldownPermission;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.cooldown.ArchiveReason;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.test.TestPanteraCaches;
import com.auto1.pantera.test.TestSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * Verifies the {@code GET /api/v1/cooldown/history} endpoint added in 2.2.0:
 * the gate (API-level {@link ApiCooldownPermission#READ} + per-repo
 * {@link AdapterBasicPermission}) and the archive-field serialisation.
 *
 * <p>Parallel to {@link CooldownHandlerFilterTest} but exercises the archive
 * table ({@code artifact_cooldowns_history}). History reuses the same
 * {@link ApiCooldownPermission#READ} gate as {@code /blocked}; per-row
 * filtering falls back to the repo-level
 * {@link AdapterBasicPermission}({@code repo}, {@code "read"}).
 *
 * @since 2.2.0
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
final class CooldownHandlerHistoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    private static final String HOST = "localhost";

    private static final long TEST_TIMEOUT = Duration.ofSeconds(10).toSeconds();

    /**
     * Permissions the test user holds. Mutated per-test.
     */
    private static volatile PermissionSpec permSpec = new PermissionSpec(false, Set.of());

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
        this.truncateHistory();
        this.truncateCooldowns();
        this.truncateRepositories();
        permSpec = new PermissionSpec(false, Set.of());
        final Storage storage = new InMemoryStorage();
        final PanteraSecurity security = new PanteraSecurity() {
            @Override
            public Authentication authentication() {
                return (name, pswd) -> Optional.of(new AuthUser("pantera", "test"));
            }

            @Override
            public Policy<?> policy() {
                return user -> CooldownHandlerHistoryTest.buildPermissions(
                    CooldownHandlerHistoryTest.permSpec
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
        this.truncateHistory();
        this.truncateCooldowns();
        this.truncateRepositories();
    }

    /**
     * A user who lacks {@link ApiCooldownPermission#READ} must get 403 even
     * when they have {@link AdapterBasicPermission} on the seeded repo —
     * the API gate is the coarse check that must fire before row scoping.
     */
    @Test
    void historyEndpointReturns403WithoutCooldownReadPermission(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        permSpec = new PermissionSpec(false, Set.of("repo-a"));
        this.seedRepo("repo-a", "npm-proxy");
        this.seedHistory(
            "npm-proxy", "repo-a", "pkg-a", "1.0.0",
            ArchiveReason.EXPIRED, "system"
        );
        this.request(
            vertx, ctx, HttpMethod.GET, "/api/v1/cooldown/history",
            res -> Assertions.assertEquals(
                403, res.statusCode(),
                "user without ApiCooldownPermission.READ must be rejected"
            )
        );
    }

    /**
     * With the history permission but only a single-repo AdapterBasicPermission,
     * the handler must return rows from that repo and no other. Mirrors the
     * per-repo scoping from the blocked endpoint but applied to the archive.
     */
    @Test
    void historyEndpointReturnsOnlyAccessibleRepoArchiveEntries(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        this.seedRepo("repo-b", "npm-proxy");
        this.seedRepo("repo-c", "npm-proxy");
        this.seedHistory("npm-proxy", "repo-a", "pkg-a", "1.0.0",
            ArchiveReason.EXPIRED, "system");
        this.seedHistory("npm-proxy", "repo-b", "pkg-b", "2.0.0",
            ArchiveReason.MANUAL_UNBLOCK, "alice");
        this.seedHistory("npm-proxy", "repo-c", "pkg-c", "3.0.0",
            ArchiveReason.EXPIRED, "system");
        permSpec = new PermissionSpec(true, Set.of("repo-a"));
        this.request(
            vertx, ctx, HttpMethod.GET, "/api/v1/cooldown/history",
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Expected 200, got body: " + res.bodyAsString());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(1, body.getInteger("total"),
                    "total must reflect permission-scoped count, not all "
                        + "history rows");
                final JsonArray items = body.getJsonArray("items");
                Assertions.assertEquals(1, items.size());
                Assertions.assertEquals(
                    "repo-a", items.getJsonObject(0).getString("repo"),
                    "row from unreadable repo leaked into response"
                );
            }
        );
    }

    /**
     * The response must carry the archive-specific fields that don't exist
     * on the blocked endpoint — this is the shape the UI relies on to
     * differentiate history rows from live blocks.
     */
    @Test
    void historyEndpointSerializesArchiveFields(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        this.seedHistory(
            "npm-proxy", "repo-a", "pkg-a", "1.0.0",
            ArchiveReason.MANUAL_UNBLOCK, "alice"
        );
        permSpec = new PermissionSpec(true, Set.of("repo-a"));
        this.request(
            vertx, ctx, HttpMethod.GET, "/api/v1/cooldown/history",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonArray items = res.bodyAsJsonObject().getJsonArray("items");
                Assertions.assertEquals(1, items.size());
                final JsonObject row = items.getJsonObject(0);
                Assertions.assertNotNull(row.getString("archived_at"),
                    "archived_at must be serialised");
                Assertions.assertEquals("MANUAL_UNBLOCK", row.getString("archive_reason"));
                Assertions.assertEquals("alice", row.getString("archived_by"));
                // Also verify the base fields match the blocked-shape so the
                // UI can reuse serialiser code.
                Assertions.assertEquals("pkg-a", row.getString("package_name"));
                Assertions.assertEquals("1.0.0", row.getString("version"));
                Assertions.assertEquals("repo-a", row.getString("repo"));
                Assertions.assertEquals("npm-proxy", row.getString("repo_type"));
            }
        );
    }

    /**
     * Combined {@code repo} + {@code repo_type} filters must AND, mirroring
     * the blocked endpoint behavior.
     */
    @Test
    void historyEndpointFiltersByRepoAndRepoType(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        this.seedRepo("repo-b", "npm-proxy");
        this.seedRepo("repo-a-maven", "maven-proxy");
        this.seedHistory("npm-proxy", "repo-a", "pkg-npm", "1.0.0",
            ArchiveReason.EXPIRED, "system");
        this.seedHistory("maven-proxy", "repo-a-maven", "pkg-mvn", "2.0.0",
            ArchiveReason.EXPIRED, "system");
        this.seedHistory("npm-proxy", "repo-b", "pkg-other", "3.0.0",
            ArchiveReason.EXPIRED, "system");
        permSpec = new PermissionSpec(
            true, Set.of("repo-a", "repo-b", "repo-a-maven")
        );
        this.request(
            vertx, ctx, HttpMethod.GET,
            "/api/v1/cooldown/history?repo=repo-a&repo_type=npm-proxy",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(1, body.getInteger("total"),
                    "repo + repo_type must AND, not OR");
                final JsonArray items = body.getJsonArray("items");
                Assertions.assertEquals(1, items.size());
                final JsonObject only = items.getJsonObject(0);
                Assertions.assertEquals("repo-a", only.getString("repo"));
                Assertions.assertEquals("npm-proxy", only.getString("repo_type"));
                Assertions.assertEquals("pkg-npm", only.getString("package_name"));
            }
        );
    }

    /**
     * Page-size constrains the items array but total reflects the full
     * scoped+filtered row count — the UI must be able to paginate.
     */
    @Test
    void historyEndpointTotalIsCorrectAcrossPages(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        for (int i = 0; i < 12; i++) {
            this.seedHistory("npm-proxy", "repo-a", "pkg-" + i, "1.0.0",
                ArchiveReason.EXPIRED, "system");
        }
        permSpec = new PermissionSpec(true, Set.of("repo-a"));
        this.request(
            vertx, ctx, HttpMethod.GET, "/api/v1/cooldown/history?size=5",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(12, body.getInteger("total"),
                    "total must equal the full scoped count, not the page "
                        + "slice size");
                Assertions.assertEquals(
                    5, body.getJsonArray("items").size(),
                    "page slice must be limited to size=5"
                );
            }
        );
    }

    // --------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------

    /**
     * Build a heterogeneous {@link Permissions} collection according to the
     * test spec.
     */
    private static PermissionCollection buildPermissions(final PermissionSpec spec) {
        final Permissions coll = new Permissions();
        if (spec.cooldownRead()) {
            coll.add(ApiCooldownPermission.READ);
        }
        for (final String repo : spec.allowedRepos()) {
            coll.add(new AdapterBasicPermission(repo, "read"));
        }
        return coll;
    }

    /**
     * Test permission spec — captures whether the user holds the API-level
     * cooldown read permission and which per-repo reads they hold.
     * @param cooldownRead whether ApiCooldownPermission.READ is granted
     * @param allowedRepos the set of repos AdapterBasicPermission(_, "read") applies to
     */
    private record PermissionSpec(
        boolean cooldownRead,
        Set<String> allowedRepos
    ) {
    }

    private void seedRepo(final String name, final String type) {
        final String config = String.format(
            "{\"repo\":{\"type\":\"%s\"}}", type
        );
        final String sql = "INSERT INTO repositories (name, type, config)"
            + " VALUES (?, ?, ?::jsonb)"
            + " ON CONFLICT (name) DO UPDATE SET type = ?, config = ?::jsonb";
        try (Connection conn = sharedDs.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, type);
            ps.setString(3, config);
            ps.setString(4, type);
            ps.setString(5, config);
            ps.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("seedRepo failed", err);
        }
    }

    /**
     * Insert a row directly into {@code artifact_cooldowns_history}. We
     * bypass the service layer so the test exercises the read path only —
     * the archive side is already covered by repository-level tests.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private void seedHistory(final String repoType, final String repoName,
        final String artifact, final String version,
        final ArchiveReason archiveReason, final String archivedBy) {
        final long now = Instant.now().toEpochMilli();
        final String sql =
            "INSERT INTO artifact_cooldowns_history("
                + "original_id, repo_type, repo_name, artifact, version, "
                + "reason, blocked_by, blocked_at, blocked_until, "
                + "installed_by, archived_at, archive_reason, archived_by"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = sharedDs.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, 0L);
            ps.setString(2, repoType);
            ps.setString(3, repoName);
            ps.setString(4, artifact);
            ps.setString(5, version);
            ps.setString(6, CooldownReason.FRESH_RELEASE.name());
            ps.setString(7, "system");
            ps.setLong(8, now - Duration.ofHours(48).toMillis());
            ps.setLong(9, now - Duration.ofHours(1).toMillis());
            ps.setNull(10, java.sql.Types.VARCHAR);
            ps.setLong(11, now);
            ps.setString(12, archiveReason.name());
            ps.setString(13, archivedBy);
            ps.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("seedHistory failed", err);
        }
    }

    private void truncateHistory() {
        try (Connection conn = sharedDs.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "TRUNCATE TABLE artifact_cooldowns_history RESTART IDENTITY"
            )) {
            ps.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("truncate history failed", err);
        }
    }

    private void truncateCooldowns() {
        try (Connection conn = sharedDs.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "TRUNCATE TABLE artifact_cooldowns RESTART IDENTITY"
            )) {
            ps.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("truncate cooldowns failed", err);
        }
    }

    private void truncateRepositories() {
        try (Connection conn = sharedDs.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "TRUNCATE TABLE repositories CASCADE"
            )) {
            ps.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("truncate repositories failed", err);
        }
    }

    private void request(final Vertx vertx, final VertxTestContext ctx,
        final HttpMethod method, final String path,
        final Consumer<HttpResponse<Buffer>> assertion) throws Exception {
        final HttpRequest<Buffer> req = WebClient.create(vertx)
            .request(method, this.port, HOST, path);
        req.bearerTokenAuthentication(this.token);
        req.send()
            .onSuccess(res -> ctx.verify(() -> {
                assertion.accept(res);
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow)
            .toCompletionStage().toCompletableFuture()
            .get(TEST_TIMEOUT, TimeUnit.SECONDS);
    }
}
