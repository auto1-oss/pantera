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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.misc.UncheckedConsumer;
import com.auto1.pantera.settings.AliasSettings;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.http.HttpStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.skyscreamer.jsonassert.JSONAssert;

/**
 * Test for {@link StorageAliasesRest}.
 */
@DisabledOnOs(OS.WINDOWS)
public final class StorageAliasesRestTest extends RestApiServerBase {

    @Test
    void listsCommonAliases(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.save(
            new Key.From(AliasSettings.FILE_NAME),
            this.yamlAliases().getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest("/api/v1/storages"),
            new UncheckedConsumer<>(
                response -> JSONAssert.assertEquals(
                    response.body().toJsonArray().encode(),
                    this.jsonAliases(),
                    true
                )
            )
        );
    }

    @Test
    void listsRepoAliases(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String rname = "my-maven";
        this.save(
            new Key.From(rname, AliasSettings.FILE_NAME),
            this.yamlAliases().getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest(String.format("/api/v1/repository/%s/storages", rname)),
            new UncheckedConsumer<>(
                response -> JSONAssert.assertEquals(
                    response.body().toJsonArray().encode(),
                    this.jsonAliases(),
                    true
                )
            )
        );
    }

    @Test
    void returnsEmptyArrayIfAliasesDoNotExists(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.requestAndAssert(
            vertx, ctx, new TestRequest("/api/v1/storages"),
            resp ->
                MatcherAssert.assertThat(
                    resp.body().toJsonArray().isEmpty(), new IsEqual<>(true)
                )
        );
    }

    @Test
    void addsNewCommonAlias(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx,
            new TestRequest(
                HttpMethod.PUT, "/api/v1/storages/new-alias",
                new JsonObject().put("type", "fs").put("path", "new/alias/path")
            ),
            resp -> {
                MatcherAssert.assertThat(
                    resp.statusCode(), new IsEqual<>(HttpStatus.CREATED_201)
                );
                MatcherAssert.assertThat(
                    new String(
                        this.storage().value(new Key.From(AliasSettings.FILE_NAME)),
                        StandardCharsets.UTF_8
                    ),
                    new IsEqual<>(
                        String.join(
                            System.lineSeparator(),
                            "storages:",
                            "  \"new-alias\":",
                            "    type: fs",
                            "    path: new/alias/path"
                        )
                    )
                );
                assertStorageCacheInvalidated();
            }
        );
    }

    @Test
    void addsRepoAlias(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String rname = "my-pypi";
        this.save(
            new Key.From(rname, AliasSettings.FILE_NAME),
            this.yamlAliases().getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest(
                HttpMethod.PUT, String.format("/api/v1/repository/%s/storages/new-alias", rname),
                new JsonObject().put("type", "fs").put("path", "new/alias/path")
            ),
            resp -> {
                MatcherAssert.assertThat(
                    resp.statusCode(), new IsEqual<>(HttpStatus.CREATED_201)
                );
                MatcherAssert.assertThat(
                    new String(
                        this.storage().value(new Key.From(rname, AliasSettings.FILE_NAME)),
                        StandardCharsets.UTF_8
                    ),
                    new IsEqual<>(
                        String.join(
                            System.lineSeparator(),
                            "storages:",
                            "  default:",
                            "    type: fs",
                            "    path: /var/pantera/repo/data",
                            "  \"redis-sto\":",
                            "    type: redis",
                            "    config: some",
                            "  \"new-alias\":",
                            "    type: fs",
                            "    path: new/alias/path"
                        )
                    )
                );
                assertStorageCacheInvalidated();
            }
        );
    }

    @Test
    void returnsNotFoundIfAliasesDoNotExists(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.requestAndAssert(
            vertx, ctx, new TestRequest(HttpMethod.DELETE, "/api/v1/storages/any"),
            resp ->
                MatcherAssert.assertThat(resp.statusCode(), new IsEqual<>(HttpStatus.NOT_FOUND_404))
        );
    }

    @Test
    void removesCommonAlias(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.save(
            new Key.From(AliasSettings.FILE_NAME),
            this.yamlAliases().getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest(HttpMethod.DELETE, "/api/v1/storages/redis-sto"),
            resp -> {
                MatcherAssert.assertThat(resp.statusCode(), new IsEqual<>(HttpStatus.OK_200));
                MatcherAssert.assertThat(
                    new String(
                        this.storage().value(new Key.From(AliasSettings.FILE_NAME)),
                        StandardCharsets.UTF_8
                    ),
                    new IsEqual<>(
                        String.join(
                            System.lineSeparator(),
                            "storages:",
                            "  default:",
                            "    type: fs",
                            "    path: /var/pantera/repo/data"
                        )
                    )
                );
                assertStorageCacheInvalidated();
            }
        );
    }

    @Test
    void removesRepoAlias(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        final String rname = "my-rpm";
        this.save(
            new Key.From(rname, AliasSettings.FILE_NAME),
            this.yamlAliases().getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest(
                HttpMethod.DELETE, String.format("/api/v1/repository/%s/storages/default", rname)
            ),
            resp -> {
                MatcherAssert.assertThat(resp.statusCode(), new IsEqual<>(HttpStatus.OK_200));
                MatcherAssert.assertThat(
                    new String(
                        this.storage().value(new Key.From(rname, AliasSettings.FILE_NAME)),
                        StandardCharsets.UTF_8
                    ),
                    new IsEqual<>(
                        String.join(
                            System.lineSeparator(),
                            "storages:",
                            "  \"redis-sto\":",
                            "    type: redis",
                            "    config: some"
                        )
                    )
                );
                assertStorageCacheInvalidated();
            }
        );
    }

    private String yamlAliases() {
        return String.join(
            "\n",
            "storages:",
            "  default:",
            "    type: fs",
            "    path: /var/pantera/repo/data",
            "  redis-sto:",
            "    type: redis",
            "    config: some"
        );
    }

    private String jsonAliases() {
        return "[{\"alias\":\"default\",\"storage\":{\"type\":\"fs\",\"path\":\"/var/pantera/repo/data\"}},{\"alias\":\"redis-sto\",\"storage\":{\"type\":\"redis\",\"config\":\"some\"}}]";
    }
}
