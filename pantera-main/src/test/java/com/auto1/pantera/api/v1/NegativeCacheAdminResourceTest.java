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

import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.http.cache.NegativeCacheRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link com.auto1.pantera.api.v1.admin.NegativeCacheAdminResource}.
 * Lives in the same package as {@link AsyncApiTestBase} because the base class
 * methods are package-private.
 * @since 2.2.0
 */
public final class NegativeCacheAdminResourceTest extends AsyncApiTestBase {

    @BeforeEach
    void seedCache() {
        final NegativeCache shared = NegativeCacheRegistry.instance().sharedCache();
        shared.cacheNotFound(new NegativeCacheKey(
            "test-group", "maven", "com.example:foo", "1.0.0"
        ));
        shared.cacheNotFound(new NegativeCacheKey(
            "test-group", "npm", "@scope/bar", "2.0.0"
        ));
    }

    @Test
    void listReturns200WithAdminRole(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/admin/neg-cache",
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "GET /admin/neg-cache should return 200");
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertTrue(body.containsKey("items"),
                    "Response must have 'items'");
                Assertions.assertTrue(body.containsKey("total"),
                    "Response must have 'total'");
                Assertions.assertTrue(body.containsKey("page"),
                    "Response must have 'page'");
            }
        );
    }

    @Test
    void listReturns401WithoutAuth(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/admin/neg-cache",
            null, null,
            res -> Assertions.assertEquals(401, res.statusCode(),
                "GET /admin/neg-cache without token should return 401")
        );
    }

    @Test
    void probeReturns200ForExistingKey(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET,
            "/api/v1/admin/neg-cache/probe?key=test-group:maven:com.example:foo:1.0.0",
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Probe should return 200");
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertTrue(body.containsKey("present"),
                    "Response must have 'present' field");
            }
        );
    }

    @Test
    void probeReturns400WithoutKey(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/admin/neg-cache/probe",
            res -> Assertions.assertEquals(400, res.statusCode(),
                "Probe without key should return 400")
        );
    }

    @Test
    void invalidateReturnsCorrectCounts(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        NegativeCacheRegistry.instance().sharedCache().cacheNotFound(
            new NegativeCacheKey("inv-scope", "maven", "org:artifact", "3.0")
        );
        this.request(
            vertx, ctx,
            HttpMethod.POST, "/api/v1/admin/neg-cache/invalidate",
            new JsonObject()
                .put("scope", "inv-scope")
                .put("repoType", "maven")
                .put("artifactName", "org:artifact")
                .put("version", "3.0"),
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Invalidate should return 200");
                final JsonObject body = res.bodyAsJsonObject();
                final JsonObject invalidated = body.getJsonObject("invalidated");
                Assertions.assertNotNull(invalidated,
                    "Response must have 'invalidated' object");
                Assertions.assertEquals(1, invalidated.getInteger("l1"),
                    "L1 should show 1 invalidated");
            }
        );
    }

    @Test
    void invalidateReturns400WhenFieldsMissing(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.POST, "/api/v1/admin/neg-cache/invalidate",
            new JsonObject().put("scope", "x"),
            res -> Assertions.assertEquals(400, res.statusCode(),
                "Invalidate with missing fields should return 400")
        );
    }

    @Test
    void invalidatePatternReturns200(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.POST, "/api/v1/admin/neg-cache/invalidate-pattern",
            new JsonObject().put("scope", "test-group"),
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Pattern invalidation should return 200");
                final JsonObject body = res.bodyAsJsonObject();
                final JsonObject invalidated = body.getJsonObject("invalidated");
                Assertions.assertNotNull(invalidated,
                    "Response must have 'invalidated' object");
                Assertions.assertTrue(invalidated.getInteger("l1") >= 0,
                    "L1 count must be >= 0");
            }
        );
    }

    @Test
    void invalidatePatternRateLimitReturns429(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final int limit = 10;
        final JsonObject patternBody = new JsonObject()
            .put("repoType", "rate-test-" + System.nanoTime());
        for (int idx = 0; idx < limit; idx++) {
            final VertxTestContext inner = new VertxTestContext();
            this.request(
                vertx, inner,
                HttpMethod.POST,
                "/api/v1/admin/neg-cache/invalidate-pattern",
                patternBody,
                res -> Assertions.assertEquals(200, res.statusCode(),
                    "Request within limit should return 200")
            );
            Assertions.assertTrue(inner.awaitCompletion(
                AsyncApiTestBase.TEST_TIMEOUT,
                java.util.concurrent.TimeUnit.SECONDS
            ));
        }
        this.request(
            vertx, ctx,
            HttpMethod.POST,
            "/api/v1/admin/neg-cache/invalidate-pattern",
            patternBody,
            res -> Assertions.assertEquals(429, res.statusCode(),
                "11th request should return 429 (rate limited)")
        );
    }

    @Test
    void statsReturns200(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/admin/neg-cache/stats",
            res -> {
                Assertions.assertEquals(200, res.statusCode(),
                    "Stats should return 200");
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertTrue(body.containsKey("enabled"),
                    "Stats must have 'enabled'");
                Assertions.assertTrue(body.containsKey("l1Size"),
                    "Stats must have 'l1Size'");
                Assertions.assertTrue(body.containsKey("hitCount"),
                    "Stats must have 'hitCount'");
                Assertions.assertTrue(body.containsKey("missCount"),
                    "Stats must have 'missCount'");
                Assertions.assertTrue(body.containsKey("hitRate"),
                    "Stats must have 'hitRate'");
            }
        );
    }
}
