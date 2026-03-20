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
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link SettingsHandler}.
 *
 * @since 1.21
 */
public final class SettingsHandlerTest extends AsyncApiTestBase {

    @Test
    void getSettingsReturnsPort(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/settings",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(body);
                Assertions.assertTrue(
                    body.containsKey("port"),
                    "Response must contain 'port' field"
                );
            }
        );
    }

    @Test
    void getSettingsReturnsVersion(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.request(
            vertx, ctx,
            HttpMethod.GET, "/api/v1/settings",
            res -> {
                Assertions.assertEquals(200, res.statusCode());
                final JsonObject body = res.bodyAsJsonObject();
                Assertions.assertNotNull(body);
                Assertions.assertNotNull(
                    body.getString("version"),
                    "Response must contain non-null 'version' field"
                );
            }
        );
    }
}
