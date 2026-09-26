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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.http.cache.NegativeCacheRegistry;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * HTTP contracts of the admin cache tools, served by a bare router (no
 * database, no containers): shapes the UI relies on, validation, 404s and
 * the admin-only gate.
 *
 * @since 2.2.9
 */
@ExtendWith(VertxExtension.class)
final class CacheToolsRoutesTest {

    /**
     * Topology.
     */
    private final FakeTopology topology = new FakeTopology()
        .proxy("npm_proxy", "npm-proxy")
        .group("npm_group", "npm-group", "npm_proxy");

    /**
     * Server.
     */
    private HttpServer server;

    /**
     * Client.
     */
    private WebClient client;

    @BeforeEach
    void start(final Vertx vertx) throws Exception {
        this.server = CacheToolsRoutesTest.serve(vertx, this.topology, Policy.FREE);
        this.client = WebClient.create(vertx);
    }

    @AfterEach
    void stop() throws Exception {
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void listReportsSourceNodeAndStructuredItems() throws Exception {
        final String scope = "qa_ct_" + UUID.randomUUID();
        NegativeCacheRegistry.instance().sharedCache().cacheNotFound(
            new NegativeCacheKey(scope, "npm-proxy", "lodash", "1.0.0")
        );
        final JsonObject body = this.call(
            HttpMethod.GET, "/api/v1/admin/neg-cache?scope=" + scope + "&q=LODA", null
        ).bodyAsJsonObject();
        MatcherAssert.assertThat("total", body.getInteger("total"), new IsEqual<>(1));
        MatcherAssert.assertThat("source", body.getString("source"), new IsEqual<>("L1-only"));
        MatcherAssert.assertThat("node", body.getString("node"), new IsEqual<>("node-t"));
        final JsonObject item = body.getJsonArray("items").getJsonObject(0);
        MatcherAssert.assertThat(
            "structured key",
            item.getJsonObject("key").getString("artifactName"), new IsEqual<>("lodash")
        );
        MatcherAssert.assertThat(
            "L1-only entries have no TTL",
            item.getValue("ttlRemainingMs"), new IsEqual<>(null)
        );
    }

    @Test
    void probeByUrlListsEveryDerivedKey() throws Exception {
        final NegativeCache cache = NegativeCacheRegistry.instance().sharedCache();
        final NegativeCacheKey member = new NegativeCacheKey("npm_proxy", "npm-proxy", "qa-ct-probe", "1.0.0");
        cache.cacheNotFound(member);
        try {
            final JsonObject body = this.call(
                HttpMethod.GET,
                "/api/v1/admin/neg-cache/probe?url=/npm_group/qa-ct-probe/-/qa-ct-probe-1.0.0.tgz",
                null
            ).bodyAsJsonObject();
            MatcherAssert.assertThat("group key + member key", body.getJsonArray("keys").size(), new IsEqual<>(2));
            MatcherAssert.assertThat("shadowed", body.getBoolean("shadowed"), new IsEqual<>(true));
            MatcherAssert.assertThat(
                "the member key is structured and present in L1",
                body.getJsonArray("keys").getJsonObject(1).getJsonObject("key").getString("scope")
                    + ":" + body.getJsonArray("keys").getJsonObject(1).getBoolean("l1"),
                new IsEqual<>("npm_proxy:true")
            );
        } finally {
            cache.invalidate(member);
        }
    }

    @Test
    void probeOfAnUnknownRepositoryIs404() throws Exception {
        MatcherAssert.assertThat(
            this.call(HttpMethod.GET, "/api/v1/admin/neg-cache/probe?url=/nope/x", null).statusCode(),
            new IsEqual<>(404)
        );
    }

    @Test
    void invalidateAnswersTopLevelAndLegacyCounts() throws Exception {
        final String scope = "qa_ct_" + UUID.randomUUID();
        NegativeCacheRegistry.instance().sharedCache().cacheNotFound(
            new NegativeCacheKey(scope, "npm-proxy", "lodash", "1.0.0")
        );
        final JsonObject body = this.call(
            HttpMethod.POST, "/api/v1/admin/neg-cache/invalidate",
            new JsonObject().put("scope", scope).put("repoType", "npm-proxy")
                .put("artifactName", "lodash").put("version", "1.0.0")
        ).bodyAsJsonObject();
        MatcherAssert.assertThat(
            "top-level and legacy counts agree",
            body.getInteger("l1") + "/" + body.getJsonObject("invalidated").getInteger("l1")
                + "/" + body.getString("node"),
            new IsEqual<>("1/1/node-t")
        );
    }

    @Test
    void invalidatePackageClearsEverySpelling() throws Exception {
        final NegativeCache cache = NegativeCacheRegistry.instance().sharedCache();
        final String artifact = "qa" + UUID.randomUUID().toString().replace("-", "");
        cache.cacheNotFound(new NegativeCacheKey("m_group", "maven-group", "com.qa." + artifact, "1.0/x.jar"));
        cache.cacheNotFound(new NegativeCacheKey("m_proxy", "maven-proxy", "com/qa/" + artifact, "1.0"));
        final JsonObject body = this.call(
            HttpMethod.POST, "/api/v1/admin/neg-cache/invalidate-package",
            new JsonObject().put("artifactName", "com.qa:" + artifact).put("repoType", "maven")
        ).bodyAsJsonObject();
        MatcherAssert.assertThat(body.getInteger("l1"), new IsEqual<>(2));
    }

    @Test
    void statsCarryL2SizeAndNode() throws Exception {
        final JsonObject body = this.call(HttpMethod.GET, "/api/v1/admin/neg-cache/stats", null)
            .bodyAsJsonObject();
        MatcherAssert.assertThat(
            "no L2 without Valkey, node named",
            body.containsKey("l2Size") + ":" + body.getValue("l2Size") + ":" + body.getString("node"),
            new IsEqual<>("true:null:node-t")
        );
    }

    @Test
    void troubleshootNeedsAUrl() throws Exception {
        MatcherAssert.assertThat(
            this.call(HttpMethod.GET, "/api/v1/admin/troubleshoot", null).statusCode(),
            new IsEqual<>(400)
        );
    }

    @Test
    void troubleshootExplainsAnUnknownRepository() throws Exception {
        final HttpResponse<Buffer> resp =
            this.call(HttpMethod.GET, "/api/v1/admin/troubleshoot?url=/nope/x", null);
        MatcherAssert.assertThat(
            "200 with a repository problem and a null repo",
            resp.statusCode() + ":" + resp.bodyAsJsonObject().getValue("repo"),
            new IsEqual<>("200:null")
        );
    }

    @Test
    void inspectValidatesItsParameters() throws Exception {
        MatcherAssert.assertThat(
            "missing package",
            this.call(HttpMethod.GET, "/api/v1/cooldown/inspect?repoType=npm", null).statusCode(),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "unknown repository",
            this.call(HttpMethod.GET, "/api/v1/cooldown/inspect?repoType=npm&package=x&repo=nope", null)
                .statusCode(),
            new IsEqual<>(404)
        );
    }

    @Test
    void inspectAnswersPerRepository() throws Exception {
        final JsonObject body = this.call(
            HttpMethod.GET, "/api/v1/cooldown/inspect?repoType=npm&package=lodash", null
        ).bodyAsJsonObject();
        MatcherAssert.assertThat(
            "both npm repositories, fetch unavailable reported per repository",
            body.getJsonArray("repos").size() + ":"
                + body.getJsonArray("repos").getJsonObject(0).getJsonObject("metadata").getInteger("status"),
            new IsEqual<>("2:0")
        );
    }

    @Test
    void refreshPackageAnswersBeforeAndAfter() throws Exception {
        final JsonObject body = this.call(
            HttpMethod.POST, "/api/v1/cooldown/refresh-package",
            new JsonObject().put("repoType", "npm").put("package", "lodash")
        ).bodyAsJsonObject();
        MatcherAssert.assertThat(
            body.containsKey("before") && body.containsKey("after") && body.containsKey("cleared"),
            new IsEqual<>(true)
        );
    }

    @Test
    void suggestAnswersPackagesInTheInspectorForm() throws Exception {
        final HttpResponse<Buffer> resp = this.call(
            HttpMethod.GET, "/api/v1/cooldown/inspect/suggest?repoType=npm&q=LODA", null
        );
        final JsonObject body = resp.bodyAsJsonObject();
        MatcherAssert.assertThat(
            "status, node and the matching package",
            resp.statusCode() + ":" + body.getString("node") + ":"
                + body.getJsonArray("suggestions").getJsonObject(0).getString("package"),
            new IsEqual<>("200:node-t:lodash")
        );
        MatcherAssert.assertThat(
            "every field the UI renders",
            body.getJsonArray("suggestions").getJsonObject(0).fieldNames(),
            new IsEqual<>(java.util.Set.of("package", "display", "repoType", "sources", "repos"))
        );
    }

    @Test
    void suggestSearchesEveryTypeWithoutARepoType() throws Exception {
        MatcherAssert.assertThat(
            this.call(HttpMethod.GET, "/api/v1/cooldown/inspect/suggest?q=dash", null)
                .bodyAsJsonObject().getJsonArray("suggestions").size(),
            new IsEqual<>(1)
        );
    }

    @Test
    void suggestValidatesItsParameters() throws Exception {
        MatcherAssert.assertThat(
            "missing q",
            this.call(HttpMethod.GET, "/api/v1/cooldown/inspect/suggest?repoType=npm", null)
                .statusCode(),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "bad limit",
            this.call(HttpMethod.GET, "/api/v1/cooldown/inspect/suggest?q=lo&limit=x", null)
                .statusCode(),
            new IsEqual<>(400)
        );
    }

    @Test
    void inspectOfAnUnknownNameOffersDidYouMean() throws Exception {
        final JsonObject body = this.call(
            HttpMethod.GET, "/api/v1/cooldown/inspect?repoType=npm&package=loda", null
        ).bodyAsJsonObject();
        MatcherAssert.assertThat(
            body.getJsonArray("didYouMean").getJsonObject(0).getString("package"),
            new IsEqual<>("lodash")
        );
    }

    @Test
    void nonAdminsCannotSuggest(final Vertx vertx) throws Exception {
        final Policy<PermissionCollection> none = user -> new Permissions();
        final HttpServer denied = CacheToolsRoutesTest.serve(vertx, this.topology, none);
        try {
            MatcherAssert.assertThat(
                WebClient.create(vertx)
                    .request(
                        HttpMethod.GET, denied.actualPort(), "localhost",
                        "/api/v1/cooldown/inspect/suggest?repoType=npm&q=lo"
                    )
                    .send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .statusCode(),
                new IsEqual<>(403)
            );
        } finally {
            denied.close();
        }
    }

    @Test
    void nonAdminsAreRefused(final Vertx vertx) throws Exception {
        final Policy<PermissionCollection> none = user -> new Permissions();
        final HttpServer denied = CacheToolsRoutesTest.serve(vertx, this.topology, none);
        try {
            MatcherAssert.assertThat(
                WebClient.create(vertx)
                    .request(HttpMethod.GET, denied.actualPort(), "localhost", "/api/v1/admin/troubleshoot?url=/x")
                    .send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .statusCode(),
                new IsEqual<>(403)
            );
        } finally {
            denied.close();
        }
    }

    /**
     * Perform a call.
     *
     * @param method Method
     * @param path Path
     * @param body Body, may be null
     * @return Response
     * @throws Exception On failure
     */
    private HttpResponse<Buffer> call(
        final HttpMethod method, final String path, final JsonObject body
    ) throws Exception {
        final var req = this.client.request(method, this.server.actualPort(), "localhost", path);
        final var fut = body == null ? req.send() : req.sendJsonObject(body);
        return fut.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /**
     * Start a router serving the cache tools as an authenticated "admin".
     *
     * @param vertx Vert.x
     * @param topology Topology
     * @param policy Policy
     * @return Server
     * @throws Exception On failure
     */
    private static HttpServer serve(
        final Vertx vertx, final RepoTopology topology, final Policy<?> policy
    ) throws Exception {
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route().handler(ctx -> {
            ctx.setUser(User.create(new JsonObject().put("sub", "admin").put("context", "test")));
            ctx.next();
        });
        final AdminDiagnostics diag = new AdminDiagnostics(
            topology, RepoFetch.UNAVAILABLE, BreakerProbe.NONE, "node-t"
        );
        final NegativeCache negative = NegativeCacheRegistry.instance().sharedCache();
        final PackageInspector inspector = new PackageInspector(
            diag, negative, Optional::empty, CooldownLookup.NONE
        );
        new NegativeCacheAdminResource(policy, diag).register(router);
        new CooldownInspectResource(
            policy, inspector,
            new PackageRefresher(
                inspector, negative, Optional::empty,
                com.auto1.pantera.cooldown.metadata.ProxyMetadataRevalidators.instance()
            ),
            new PackageSuggester(new NpmNames())
        ).register(router);
        new TroubleshootResource(policy, new Troubleshooter(diag, negative, inspector))
            .register(router);
        return vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Suggestion source knowing one npm package.
     *
     * @since 2.2.9
     */
    private static final class NpmNames implements SuggestLookup {

        @Override
        public java.util.List<SuggestRow> rows(
            final java.util.Collection<String> families, final SuggestQuery query, final int limit
        ) {
            final SuggestRow row = new SuggestRow(SuggestRow.INDEX, "npm-proxy", "npm_proxy", "lodash", null);
            final java.util.List<SuggestRow> out;
            if (query.matches(java.util.List.of(row.name()))
                && (families.isEmpty() || families.contains("npm"))) {
                out = java.util.List.of(row);
            } else {
                out = java.util.List.of();
            }
            return out;
        }

        @Override
        public java.util.Map<String, String> mavenPaths(final java.util.Collection<String> names) {
            return java.util.Map.of();
        }
    }
}
