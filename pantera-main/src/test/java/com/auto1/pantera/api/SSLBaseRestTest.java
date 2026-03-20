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
package com.auto1.pantera.api;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import java.util.Optional;
import org.eclipse.jetty.http.HttpStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

/**
 * Base test for SSL.
 * @since 0.26
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
abstract class SSLBaseRestTest extends RestApiServerBase {
    @Test
    void generatesToken(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx, new TestRequest(
                HttpMethod.POST, "/api/v1/oauth/token",
                new JsonObject().put("name", "Alice").put("pass", "wonderland")
            ), Optional.empty(),
            response -> {
                MatcherAssert.assertThat(
                    response.statusCode(),
                    new IsEqual<>(HttpStatus.OK_200)
                );
                MatcherAssert.assertThat(
                    response.body().toString(),
                    new StringContains("{\"token\":")
                );
            }
        );
    }

    @Override
    PanteraSecurity auth() {
        return new PanteraSecurity() {
            @Override
            public Authentication authentication() {
                return new Authentication.Single("Alice", "wonderland");
            }

            @Override
            public Policy<?> policy() {
                return Policy.FREE;
            }

            @Override
            public Optional<Storage> policyStorage() {
                return Optional.empty();
            }
        };
    }

}
