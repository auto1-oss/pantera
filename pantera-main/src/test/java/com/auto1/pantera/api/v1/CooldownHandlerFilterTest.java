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
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.api.perms.ApiCooldownPermission;
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
import java.util.concurrent.CompletableFuture;
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
 * Verifies the SQL-pushed permission scoping + repo/repo_type filters in
 * {@link CooldownHandler#blocked}.
 *
 * <p>This test spins up a full {@link AsyncApiVerticle} (mirroring
 * {@link AsyncApiTestBase}) but with a <b>test-controlled</b> security policy
 * that lets each test limit which repositories the single test user can read.
 * The stock {@code AsyncApiTestBase} hardcodes {@link Policy#FREE}, which
 * implies every permission — that harness cannot demonstrate per-repo
 * scoping, so a parallel setup is required.
 *
 * <p>Coverage:
 * <ul>
 *   <li>permission scoping hides rows from repos the caller cannot read</li>
 *   <li>{@code ?repo=} narrows to a single repo</li>
 *   <li>{@code ?repo_type=} narrows to a single type</li>
 *   <li>combined filters intersect</li>
 *   <li>{@code total} in the paginated response matches the
 *       permission-scoped filtered row count (the 2.1.x off-by-N
 *       {@code filteredTotal} bug is fixed by this rewrite)</li>
 * </ul>
 * @since 2.2.0
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
final class CooldownHandlerFilterTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    private static final String HOST = "localhost";

    private static final long TEST_TIMEOUT = Duration.ofSeconds(10).toSeconds();

    /**
     * Repos the test user is allowed to read. Mutated per-test in setUp.
     */
    private static volatile Set<String> allowedRepos = Set.of();

    private static HikariDataSource sharedDs;

    private String token;

    private int port;

    /**
     * Stand-in for the cooldown service the repository slices serve with.
     */
    private RecordingCooldownService servingCooldown;

    /**
     * Stand-in for the metadata service the repository slices serve with.
     */
    private RecordingMetadataService servingMetadata;

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
        this.truncateCooldowns();
        this.truncateRepositories();
        this.servingCooldown = new RecordingCooldownService();
        this.servingMetadata = new RecordingMetadataService();
        // Default: user has no perms — individual tests override before seeding.
        allowedRepos = Set.of();
        final Storage storage = new InMemoryStorage();
        final PanteraSecurity security = new PanteraSecurity() {
            @Override
            public Authentication authentication() {
                return (name, pswd) -> Optional.of(new AuthUser("pantera", "test"));
            }

            @Override
            public Policy<?> policy() {
                return user -> CooldownHandlerFilterTest.buildPermissions(
                    CooldownHandlerFilterTest.allowedRepos
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
            this.servingCooldown,
            this.servingMetadata,
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
        this.truncateCooldowns();
        this.truncateRepositories();
    }

    @Test
    void blockedReturnsOnlyAccessibleRepos(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        this.seedRepo("repo-b", "npm-proxy");
        this.seedRepo("repo-c", "npm-proxy");
        this.seedBlock("npm-proxy", "repo-a", "pkg-a", "1.0.0");
        this.seedBlock("npm-proxy", "repo-b", "pkg-b", "2.0.0");
        this.seedBlock("npm-proxy", "repo-c", "pkg-c", "3.0.0");
        allowedRepos = Set.of("repo-a", "repo-b");
        this.request(
            vertx, ctx, HttpMethod.GET, "/api/v1/cooldown/blocked",
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Expected 200, got body: " + res.bodyAsString());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(2, body.getInteger("total"),
                    "total must reflect permission-scoped count, not the "
                        + "full table");
                final JsonArray items = body.getJsonArray("items");
                Assertions.assertEquals(2, items.size(),
                    "items must contain only rows from accessible repos");
                for (int i = 0; i < items.size(); i++) {
                    final String repo = items.getJsonObject(i).getString("repo");
                    Assertions.assertTrue(
                        "repo-a".equals(repo) || "repo-b".equals(repo),
                        "unexpected repo leaked past perm scope: " + repo
                    );
                }
            }
        );
    }

    @Test
    void blockedFiltersByRepoName(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        this.seedRepo("repo-b", "npm-proxy");
        this.seedRepo("repo-c", "npm-proxy");
        this.seedBlock("npm-proxy", "repo-a", "pkg-1", "1.0.0");
        this.seedBlock("npm-proxy", "repo-a", "pkg-2", "1.0.0");
        this.seedBlock("npm-proxy", "repo-b", "pkg-3", "1.0.0");
        this.seedBlock("npm-proxy", "repo-c", "pkg-4", "1.0.0");
        allowedRepos = Set.of("repo-a", "repo-b", "repo-c");
        this.request(
            vertx, ctx, HttpMethod.GET, "/api/v1/cooldown/blocked?repo=repo-a",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(2, body.getInteger("total"),
                    "total must reflect only repo-a rows");
                final JsonArray items = body.getJsonArray("items");
                Assertions.assertEquals(2, items.size());
                for (int i = 0; i < items.size(); i++) {
                    Assertions.assertEquals(
                        "repo-a", items.getJsonObject(i).getString("repo"),
                        "repo filter must match every row"
                    );
                }
            }
        );
    }

    @Test
    void blockedFiltersByRepoType(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        this.seedRepo("repo-b", "maven-proxy");
        this.seedBlock("npm-proxy", "repo-a", "pkg-a1", "1.0.0");
        this.seedBlock("npm-proxy", "repo-a", "pkg-a2", "1.0.0");
        this.seedBlock("maven-proxy", "repo-b", "pkg-b1", "1.0.0");
        allowedRepos = Set.of("repo-a", "repo-b");
        this.request(
            vertx, ctx, HttpMethod.GET,
            "/api/v1/cooldown/blocked?repo_type=npm-proxy",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(2, body.getInteger("total"));
                final JsonArray items = body.getJsonArray("items");
                Assertions.assertEquals(2, items.size());
                for (int i = 0; i < items.size(); i++) {
                    Assertions.assertEquals(
                        "npm-proxy",
                        items.getJsonObject(i).getString("repo_type")
                    );
                }
            }
        );
    }

    /**
     * The UI sends base repo-type values ({@code docker}, {@code npm},
     * {@code maven}) while the database column stores full subtyped values
     * ({@code docker-proxy}, {@code docker-group}, ...). The repo_type
     * filter must match rows whose repo_type starts with {@code <base>-}
     * in addition to an exact match, so a user asking for "docker" sees
     * both {@code docker-proxy} and {@code docker-group} rows.
     */
    @Test
    void blockedFiltersByRepoTypeBase(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-proxy", "docker-proxy");
        this.seedRepo("repo-group", "docker-group");
        this.seedRepo("repo-maven", "maven-proxy");
        this.seedBlock("docker-proxy", "repo-proxy", "pkg-dp", "1.0.0");
        this.seedBlock("docker-group", "repo-group", "pkg-dg", "1.0.0");
        this.seedBlock("maven-proxy", "repo-maven", "pkg-mp", "1.0.0");
        allowedRepos = Set.of("repo-proxy", "repo-group", "repo-maven");
        this.request(
            vertx, ctx, HttpMethod.GET,
            "/api/v1/cooldown/blocked?repo_type=docker",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(2, body.getInteger("total"),
                    "base 'docker' must match both 'docker-proxy' and "
                        + "'docker-group' rows");
                final JsonArray items = body.getJsonArray("items");
                Assertions.assertEquals(2, items.size());
                for (int i = 0; i < items.size(); i++) {
                    Assertions.assertTrue(
                        items.getJsonObject(i).getString("repo_type")
                            .startsWith("docker"),
                        "row with non-docker repo_type leaked into response"
                    );
                }
            }
        );
    }

    @Test
    void blockedTotalMatchesFilteredRowsAfterPermScoping(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        this.seedRepo("repo-b", "npm-proxy");
        this.seedRepo("repo-c", "npm-proxy");
        for (int i = 0; i < 5; i++) {
            this.seedBlock("npm-proxy", "repo-a", "pkg-a" + i, "1.0.0");
            this.seedBlock("npm-proxy", "repo-b", "pkg-b" + i, "1.0.0");
            this.seedBlock("npm-proxy", "repo-c", "pkg-c" + i, "1.0.0");
        }
        // User has perms on repo-a + repo-b (10 accessible out of 15 total).
        allowedRepos = Set.of("repo-a", "repo-b");
        this.request(
            vertx, ctx, HttpMethod.GET,
            "/api/v1/cooldown/blocked?size=5",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertEquals(10, body.getInteger("total"),
                    "total must equal permission-scoped row count (10), "
                        + "not the raw table size (15) and not the page "
                        + "size (5). This is the previously-buggy "
                        + "filteredTotal that the SQL rewrite fixes.");
                Assertions.assertEquals(
                    5, body.getJsonArray("items").size(),
                    "page slice must be limited to size=5"
                );
            }
        );
    }

    @Test
    void blockedCombinesFiltersWithAnd(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("repo-a", "npm-proxy");
        this.seedRepo("repo-b", "npm-proxy");
        this.seedBlock("npm-proxy", "repo-a", "match-me", "1.0.0");
        this.seedBlock("maven-proxy", "repo-a", "skip-wrong-type", "1.0.0");
        this.seedBlock("npm-proxy", "repo-b", "skip-wrong-repo", "1.0.0");
        allowedRepos = Set.of("repo-a", "repo-b");
        this.request(
            vertx, ctx, HttpMethod.GET,
            "/api/v1/cooldown/blocked?repo=repo-a&repo_type=npm-proxy",
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
                Assertions.assertEquals("match-me", only.getString("package_name"));
            }
        );
    }

    @Test
    void blockedRejectsRequestsWithoutAuth(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        // Token=null means no Authorization header. The unified JWT auth
        // filter rejects with 401 before reaching blocked().
        this.requestRaw(
            vertx, ctx, HttpMethod.GET, "/api/v1/cooldown/blocked", null,
            res -> Assertions.assertEquals(401, res.statusCode(),
                "requests without a bearer token must be rejected upstream "
                    + "of blocked()")
        );
    }

    // --------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------

    /**
     * Build a heterogeneous {@link Permissions} collection that:
     * <ul>
     *   <li>implies {@link ApiCooldownPermission#READ} so the AuthzHandler
     *       admits the request;</li>
     *   <li>implies {@code AdapterBasicPermission(repo, "read")} for every
     *       repo in {@code allowed} and for no other repo.</li>
     * </ul>
     * Using {@link Permissions} (JDK heterogeneous collection) so the two
     * permission types dispatch to their own typed collections.
     */
    private static PermissionCollection buildPermissions(final Set<String> allowed) {
        final Permissions coll = new Permissions();
        // Let AuthzHandler pass for /api/v1/cooldown/*.
        coll.add(ApiCooldownPermission.READ);
        coll.add(ApiCooldownPermission.WRITE);
        for (final String repo : allowed) {
            coll.add(new AdapterBasicPermission(repo, "read"));
        }
        return coll;
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

    private void seedBlock(final String repoType, final String repoName,
        final String artifact, final String version) {
        final long now = Instant.now().toEpochMilli();
        final String sql =
            "INSERT INTO artifact_cooldowns(repo_type, repo_name, artifact, "
                + "version, reason, status, blocked_by, blocked_at, "
                + "blocked_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = sharedDs.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoType);
            ps.setString(2, repoName);
            ps.setString(3, artifact);
            ps.setString(4, version);
            ps.setString(5, CooldownReason.FRESH_RELEASE.name());
            ps.setString(6, "ACTIVE");
            ps.setString(7, "system");
            ps.setLong(8, now);
            ps.setLong(9, now + Duration.ofHours(72).toMillis());
            ps.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("seedBlock failed", err);
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
        // Many tables reference repositories(name) via FK — use CASCADE so
        // DAO-seeded rows in sibling tables don't block the truncate.
        try (Connection conn = sharedDs.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "TRUNCATE TABLE repositories CASCADE"
            )) {
            ps.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("truncate repositories failed", err);
        }
    }

    @Test
    void unblockReachesTheInjectedServingServices(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        // 2026-09-23: each API verticle built its own cooldown stack, so an
        // admin unblock never touched the caches the slices serve from.
        this.seedRepo("npm_proxy", "npm-proxy");
        allowedRepos = Set.of("npm_proxy");
        this.postJson(
            vertx, ctx, "/api/v1/repositories/npm_proxy/cooldown/unblock",
            new JsonObject().put("artifact", "@scope/pkg").put("version", "2.1.280"),
            res -> {
                Assertions.assertEquals(204, res.statusCode(),
                    "unblock must succeed, got body: " + res.bodyAsString());
                Assertions.assertEquals(
                    java.util.List.of("npm-proxy:npm_proxy:@scope/pkg:2.1.280"),
                    this.servingCooldown.unblocks,
                    "unblock must reach the serving cooldown service"
                );
                Assertions.assertEquals(
                    java.util.List.of("npm-proxy:npm_proxy:@scope/pkg"),
                    this.servingMetadata.invalidations,
                    "unblock must drop the serving filtered-metadata envelopes"
                );
            }
        );
    }

    @Test
    void unblockAllReachesTheInjectedServingServices(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.seedRepo("npm_proxy", "npm-proxy");
        allowedRepos = Set.of("npm_proxy");
        this.postJson(
            vertx, ctx, "/api/v1/repositories/npm_proxy/cooldown/unblock-all",
            new JsonObject(),
            res -> {
                Assertions.assertEquals(204, res.statusCode(),
                    "unblock-all must succeed, got body: " + res.bodyAsString());
                Assertions.assertEquals(
                    java.util.List.of("npm-proxy:npm_proxy"),
                    this.servingCooldown.unblockAlls,
                    "unblock-all must reach the serving cooldown service"
                );
                Assertions.assertEquals(
                    java.util.List.of("npm-proxy:npm_proxy"),
                    this.servingMetadata.repoInvalidations,
                    "unblock-all must drop the serving envelopes for the repo"
                );
            }
        );
    }

    private void postJson(final Vertx vertx, final VertxTestContext ctx,
        final String path, final JsonObject body,
        final Consumer<HttpResponse<Buffer>> assertion) throws Exception {
        WebClient.create(vertx)
            .request(HttpMethod.POST, this.port, HOST, path)
            .bearerTokenAuthentication(this.token)
            .sendJsonObject(body)
            .onSuccess(res -> ctx.verify(() -> {
                assertion.accept(res);
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow)
            .toCompletionStage().toCompletableFuture()
            .get(TEST_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * Cooldown service that records unblock calls; everything else is a no-op.
     */
    private static final class RecordingCooldownService implements CooldownService {
        private final java.util.List<String> unblocks =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<String> unblockAlls =
            new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(final String repoType, final String repoName,
            final String artifact, final String version, final String actor) {
            this.unblocks.add(repoType + ":" + repoName + ":" + artifact + ":" + version);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(final String repoType,
            final String repoName, final String actor) {
            this.unblockAlls.add(repoType + ":" + repoName);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<java.util.List<CooldownBlock>> activeBlocks(
            final String repoType, final String repoName
        ) {
            return CompletableFuture.completedFuture(java.util.List.of());
        }
    }

    /**
     * Metadata service that records invalidations; filtering is pass-through.
     */
    private static final class RecordingMetadataService implements CooldownMetadataService {
        private final java.util.List<String> invalidations =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<String> repoInvalidations =
            new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(final String repoType,
            final String repoName, final String packageName, final byte[] rawMetadata,
            final MetadataParser<T> parser, final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter) {
            return CompletableFuture.completedFuture(rawMetadata);
        }

        @Override
        public void invalidate(final String repoType, final String repoName,
            final String packageName) {
            this.invalidations.add(repoType + ":" + repoName + ":" + packageName);
        }

        @Override
        public void invalidateAll(final String repoType, final String repoName) {
            this.repoInvalidations.add(repoType + ":" + repoName);
        }

        @Override
        public void clearAll() {
            // not exercised
        }

        @Override
        public String stats() {
            return "recording";
        }
    }

    private void request(final Vertx vertx, final VertxTestContext ctx,
        final HttpMethod method, final String path,
        final Consumer<HttpResponse<Buffer>> assertion) throws Exception {
        this.requestRaw(vertx, ctx, method, path, this.token, assertion);
    }

    private void requestRaw(final Vertx vertx, final VertxTestContext ctx,
        final HttpMethod method, final String path, final String bearer,
        final Consumer<HttpResponse<Buffer>> assertion) throws Exception {
        final HttpRequest<Buffer> req = WebClient.create(vertx)
            .request(method, this.port, HOST, path);
        if (bearer != null) {
            req.bearerTokenAuthentication(bearer);
        }
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
