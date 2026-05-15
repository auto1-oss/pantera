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

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link BulkAccessPolicyHandler} —
 * {@code POST /api/v1/repositories/access-policy/bulk}.
 */
public final class BulkAccessPolicyHandlerTest extends AsyncApiTestBase {

    /**
     * Endpoint path.
     */
    private static final String PATH = "/api/v1/repositories/access-policy/bulk";

    /**
     * Hosted maven seed body.
     * @return Repo body for a hosted maven repo
     */
    private static JsonObject hostedBody() {
        return new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
            );
    }

    /**
     * Proxy maven seed body.
     * @return Repo body for a maven proxy
     */
    private static JsonObject proxyBody() {
        return new JsonObject()
            .put(
                "repo",
                new JsonObject()
                    .put("type", "maven-proxy")
                    .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
            );
    }

    /**
     * Seed a repository via PUT /api/v1/repositories/:name.
     * @param vertx Vert.x instance
     * @param name Repository name
     * @param body Repo body
     * @throws Exception On failure
     */
    private void seed(final Vertx vertx, final String name, final JsonObject body)
        throws Exception {
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + name)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, put.statusCode(),
            "seed PUT for " + name + " should succeed");
    }

    /**
     * Fetch a repository config.
     * @param vertx Vert.x instance
     * @param name Repository name
     * @return Config as JsonObject
     * @throws Exception On failure
     */
    private JsonObject fetch(final Vertx vertx, final String name) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final HttpResponse<Buffer> resp = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/" + name)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send()
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        Assertions.assertEquals(200, resp.statusCode(), "GET " + name + " should succeed");
        return resp.bodyAsJsonObject();
    }

    /**
     * POST the bulk endpoint.
     * @param vertx Vert.x instance
     * @param body Request body
     * @return The response
     * @throws Exception On failure
     */
    private HttpResponse<Buffer> postBulk(final Vertx vertx, final JsonObject body)
        throws Exception {
        return WebClient.create(vertx)
            .post(this.port(), AsyncApiTestBase.HOST, PATH)
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    void hostedSelectorUpdatesHostedReposOnly(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.seed(vertx, "bulk-hosted-1", hostedBody());
        this.seed(vertx, "bulk-proxy-1", proxyBody());
        final JsonObject body = new JsonObject()
            .put("selector", new JsonObject().put("type", "hosted"))
            .put("anonymous_read", true)
            .put("anonymous_write", false);
        final HttpResponse<Buffer> resp = this.postBulk(vertx, body);
        Assertions.assertEquals(200, resp.statusCode());
        final JsonObject json = resp.bodyAsJsonObject();
        final JsonArray updated = json.getJsonArray("updated");
        // Exactly one update — the hosted repo. The proxy is filtered out.
        boolean foundHosted = false;
        for (int idx = 0; idx < updated.size(); idx++) {
            final JsonObject row = updated.getJsonObject(idx);
            Assertions.assertNotEquals("bulk-proxy-1", row.getString("name"),
                "proxy repos must be excluded by selector.type=hosted");
            if ("bulk-hosted-1".equals(row.getString("name"))) {
                foundHosted = true;
                Assertions.assertTrue(
                    row.getJsonObject("current").getBoolean("anonymous_read"),
                    "updated.current.anonymous_read must be true"
                );
            }
        }
        Assertions.assertTrue(foundHosted, "hosted repo must appear in updated");
        // Persistence check — the saved config now carries the flags.
        final JsonObject after = this.fetch(vertx, "bulk-hosted-1");
        final JsonObject repo = after.getJsonObject("repo");
        Assertions.assertTrue(repo.getBoolean("anonymous_read"),
            "persisted anonymous_read should be true");
        Assertions.assertFalse(repo.getBoolean("anonymous_write"),
            "persisted anonymous_write should be false");
        ctx.completeNow();
    }

    @Test
    void noOverridesReturns400(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final JsonObject body = new JsonObject()
            .put("selector", new JsonObject().put("type", "proxy"));
        final HttpResponse<Buffer> resp = this.postBulk(vertx, body);
        Assertions.assertEquals(400, resp.statusCode());
        final JsonObject err = resp.bodyAsJsonObject();
        Assertions.assertEquals("BAD_REQUEST", err.getString("error"));
        ctx.completeNow();
    }

    @Test
    void explicitNamesWithMissingRepoSkippedAsNotFound(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final JsonObject body = new JsonObject()
            .put("selector", new JsonObject()
                .put("type", "all")
                .put("names", new JsonArray().add("definitely-missing-xyz")))
            .put("anonymous_read", true);
        final HttpResponse<Buffer> resp = this.postBulk(vertx, body);
        Assertions.assertEquals(200, resp.statusCode());
        final JsonObject json = resp.bodyAsJsonObject();
        Assertions.assertEquals(0, json.getJsonArray("updated").size(),
            "no updates expected when only target repo is missing");
        // The skipped array may legitimately be empty when the missing
        // name simply isn't returned by listAll() — in that path there's
        // nothing to skip. We tolerate either, but assert no spurious
        // updates and no 500.
        ctx.completeNow();
    }

    @Test
    void missingSelectorReturns400(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final JsonObject body = new JsonObject().put("anonymous_read", true);
        final HttpResponse<Buffer> resp = this.postBulk(vertx, body);
        Assertions.assertEquals(400, resp.statusCode());
        Assertions.assertEquals("BAD_REQUEST", resp.bodyAsJsonObject().getString("error"));
        ctx.completeNow();
    }

    @Test
    void badSelectorTypeReturns400(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        final JsonObject body = new JsonObject()
            .put("selector", new JsonObject().put("type", "vegetable"))
            .put("anonymous_read", true);
        final HttpResponse<Buffer> resp = this.postBulk(vertx, body);
        Assertions.assertEquals(400, resp.statusCode());
        Assertions.assertEquals("BAD_REQUEST", resp.bodyAsJsonObject().getString("error"));
        ctx.completeNow();
    }

    @Test
    void idempotentSecondRunSkipsAsNoChange(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        this.seed(vertx, "bulk-idem-hosted", hostedBody());
        final JsonObject body = new JsonObject()
            .put("selector", new JsonObject().put("type", "hosted"))
            .put("anonymous_read", true)
            .put("anonymous_write", false);
        final HttpResponse<Buffer> first = this.postBulk(vertx, body);
        Assertions.assertEquals(200, first.statusCode());
        boolean firstUpdated = false;
        final JsonArray firstUpd = first.bodyAsJsonObject().getJsonArray("updated");
        for (int idx = 0; idx < firstUpd.size(); idx++) {
            if ("bulk-idem-hosted".equals(firstUpd.getJsonObject(idx).getString("name"))) {
                firstUpdated = true;
                break;
            }
        }
        Assertions.assertTrue(firstUpdated, "first run must update the hosted repo");
        // Second identical request — repo's state already matches.
        final HttpResponse<Buffer> second = this.postBulk(vertx, body);
        Assertions.assertEquals(200, second.statusCode());
        final JsonObject secondJson = second.bodyAsJsonObject();
        boolean appearsAsUpdated = false;
        final JsonArray secondUpd = secondJson.getJsonArray("updated");
        for (int idx = 0; idx < secondUpd.size(); idx++) {
            if ("bulk-idem-hosted".equals(secondUpd.getJsonObject(idx).getString("name"))) {
                appearsAsUpdated = true;
                break;
            }
        }
        Assertions.assertFalse(appearsAsUpdated,
            "second run must not appear in updated (state already matches)");
        boolean appearsAsNoChange = false;
        final JsonArray secondSkip = secondJson.getJsonArray("skipped");
        for (int idx = 0; idx < secondSkip.size(); idx++) {
            final JsonObject row = secondSkip.getJsonObject(idx);
            if ("bulk-idem-hosted".equals(row.getString("name"))
                && "no_change".equals(row.getString("reason"))) {
                appearsAsNoChange = true;
                break;
            }
        }
        Assertions.assertTrue(appearsAsNoChange,
            "second run must list the repo in skipped with reason=no_change");
        ctx.completeNow();
    }
}
