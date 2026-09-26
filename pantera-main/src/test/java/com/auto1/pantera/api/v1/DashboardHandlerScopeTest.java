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

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.db.dao.RepositoryDao;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the dashboard information leak
 * ({@code deploy-infra} → dashboard authz): before 2.2.9 the dashboard
 * routes carried no authorization and served one global,
 * authorization-insensitive cached snapshot, so any valid access-token
 * holder — even a principal with ZERO repository read grants — received the
 * global repository/artifact/storage/cooldown counts plus the names, types,
 * artifact counts and sizes of the top repositories, including ones the
 * caller may not list.
 *
 * <p>The policy grants nothing. The dashboard must aggregate only over
 * repositories the caller can read: zero of them here.</p>
 *
 * @since 2.2.9
 */
public final class DashboardHandlerScopeTest extends AsyncApiTestBase {

    private static final String SECRET_REPO = "dashboard-scope-secret-repo";

    @BeforeAll
    static void seed() throws Exception {
        // Seed the repository through persistence (the zero-grant caller
        // under test could not create it through the API), plus one artifact
        // row, and refresh the materialised view the dashboard reads.
        new RepositoryDao(AsyncApiTestBase.sharedDs()).save(
            new RepositoryName.Simple(SECRET_REPO),
            Json.createReader(new StringReader(
                "{\"repo\":{\"type\":\"maven\",\"storage\":{\"type\":\"fs\",\"path\":\"/tmp\"}}}"
            )).readObject(),
            "seed"
        );
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO artifacts (repo_type, repo_name, name, version, size, "
                     + "created_date, owner) VALUES (?,?,?,?,?,?,?)"
             )) {
            stmt.setString(1, "maven");
            stmt.setString(2, SECRET_REPO);
            stmt.setString(3, "dashboard-probe");
            stmt.setString(4, "1.0.0");
            stmt.setLong(5, 4096L);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.setString(7, "system");
            stmt.executeUpdate();
        }
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("REFRESH MATERIALIZED VIEW mv_artifact_per_repo");
            stmt.execute("REFRESH MATERIALIZED VIEW mv_artifact_totals");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (Connection conn = AsyncApiTestBase.sharedDs().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM artifacts WHERE repo_name = '" + SECRET_REPO + "'");
            stmt.execute("REFRESH MATERIALIZED VIEW mv_artifact_per_repo");
            stmt.execute("REFRESH MATERIALIZED VIEW mv_artifact_totals");
        }
        new RepositoryDao(AsyncApiTestBase.sharedDs())
            .delete(new RepositoryName.Simple(SECRET_REPO));
    }

    @Override
    protected Policy<?> testPolicy() {
        // Authenticated, but no grants whatsoever.
        final PermissionCollection none = new Permissions();
        return user -> none;
    }

    @Test
    void zeroGrantUserSeesNoRepositoryNamesOrGlobalInventory(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> res = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/dashboard/stats")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat("dashboard must still answer", res.statusCode(), new IsEqual<>(200));
        final JsonObject body = res.bodyAsJsonObject();
        final JsonArray top = body.getJsonArray("top_repos");
        boolean leaked = false;
        for (int idx = 0; idx < top.size(); idx = idx + 1) {
            if (SECRET_REPO.equals(top.getJsonObject(idx).getString("name"))) {
                leaked = true;
            }
        }
        MatcherAssert.assertThat(
            "top_repos must not name a repository the caller cannot read",
            leaked, new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "repo_count must count only readable repositories (none here), not the global inventory",
            body.getInteger("repo_count"), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "artifact_count must be scoped, not the global total",
            body.getLong("artifact_count"), new IsEqual<>(0L)
        );
        ctx.completeNow();
    }
}
