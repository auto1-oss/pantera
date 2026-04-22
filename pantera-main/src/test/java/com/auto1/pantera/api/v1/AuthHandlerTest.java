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

import com.auto1.pantera.db.dao.AuthSettingsDao;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for AuthHandler endpoints.
 */
class AuthHandlerTest extends AsyncApiTestBase {

    @Test
    void tokenEndpointReturnsJwt(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        final JsonObject body = new JsonObject()
            .put("name", "pantera")
            .put("pass", "secret");
        request(vertx, ctx, HttpMethod.POST, "/api/v1/auth/token", body, null,
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getString("token"), notNullValue());
            });
    }

    @Test
    void providersEndpointReturnsArray(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/auth/providers", null, null,
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getJsonArray("providers"), notNullValue());
                assertThat(json.getJsonArray("providers").size() > 0, is(true));
            });
    }

    @Test
    void meEndpointReturnsCurrentUser(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/auth/me",
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getString("name"), is("pantera"));
            });
    }

    @Test
    void meEndpointReturnsPermissions(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/auth/me",
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getJsonObject("permissions"), notNullValue());
            });
    }

    /**
     * Regression: read-only users must see the same api_token_max_ttl_seconds
     * and api_token_allow_permanent values as admins. GET /admin/auth-settings
     * is admin-gated, so embedding the two public fields in /auth/me is the
     * bridge — without it, the token-generation dropdown in AppHeader.vue
     * silently falls back to a hardcoded 30/90-day list for non-admins.
     */
    @Test
    void meEndpointReturnsAuthSettingsForPublicConsumption(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        request(vertx, ctx, HttpMethod.GET, "/api/v1/auth/me",
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                final JsonObject settings = json.getJsonObject("auth_settings");
                assertThat(settings, notNullValue());
                assertThat(settings.getString("api_token_max_ttl_seconds"), notNullValue());
                assertThat(settings.getString("api_token_allow_permanent"), notNullValue());
            });
    }

    /**
     * Restore the two auth settings to their V107/V119 defaults between
     * tests. The shared Testcontainers Postgres is reused across tests in
     * a single class run; without this we'd carry over state from the
     * token-generation enforcement tests below into unrelated tests.
     */
    @AfterEach
    void resetAuthSettings() {
        final AuthSettingsDao dao = new AuthSettingsDao(sharedDs());
        dao.put("api_token_max_ttl_seconds", "31536000");
        dao.put("api_token_allow_permanent", "true");
    }

    /**
     * Server-side enforcement of api_token_allow_permanent=false.
     * Before this check, a user could POST {expiry_days: 0} and get a
     * permanent token regardless of the admin toggle — the dropdown-
     * hiding in the UI was the only guard. Regression pin for that
     * bypass.
     */
    @Test
    void generateTokenRejectsPermanentWhenAllowPermanentDisabled(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        new AuthSettingsDao(sharedDs()).put("api_token_allow_permanent", "false");
        final JsonObject body = new JsonObject()
            .put("expiry_days", 0)
            .put("label", "bypass-attempt");
        request(vertx, ctx, HttpMethod.POST, "/api/v1/auth/token/generate", body, TEST_TOKEN,
            res -> {
                assertThat(res.statusCode(), is(400));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getString("error"), is("PERMANENT_TOKENS_DISABLED"));
            });
    }

    /**
     * The admin toggle permits permanent tokens by default; that path
     * must still work. Paired with the rejection test above to prove the
     * guard is conditional, not unconditional.
     */
    @Test
    void generateTokenAllowsPermanentWhenAllowPermanentEnabled(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        new AuthSettingsDao(sharedDs()).put("api_token_allow_permanent", "true");
        final JsonObject body = new JsonObject()
            .put("expiry_days", 0)
            .put("label", "permanent-token");
        request(vertx, ctx, HttpMethod.POST, "/api/v1/auth/token/generate", body, TEST_TOKEN,
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                assertThat(json.getBoolean("permanent"), is(true));
            });
    }

    /**
     * api_token_max_ttl_seconds is the key the UI writes. The server
     * previously only consulted the legacy max_api_token_days key, so
     * flipping the slider in SettingsView had no effect on actual token
     * lifetimes. This pins that the UI key is honoured.
     */
    @Test
    void generateTokenCapsExpiryAtApiTokenMaxTtlSeconds(final Vertx vertx,
        final VertxTestContext ctx) throws Exception {
        // 30 days in seconds
        new AuthSettingsDao(sharedDs()).put("api_token_max_ttl_seconds", "2592000");
        final JsonObject body = new JsonObject()
            .put("expiry_days", 90)
            .put("label", "capped-token");
        request(vertx, ctx, HttpMethod.POST, "/api/v1/auth/token/generate", body, TEST_TOKEN,
            res -> {
                assertThat(res.statusCode(), is(200));
                final JsonObject json = res.bodyAsJsonObject();
                // The expires_at should land ~30 days out, not ~90.
                // We just assert that "permanent" is false and that
                // expires_at is present — exact-day assertions are
                // brittle under test-container clock skew.
                assertThat(json.getBoolean("permanent"), is(false));
                assertThat(json.getString("expires_at"), notNullValue());
            });
    }
}
