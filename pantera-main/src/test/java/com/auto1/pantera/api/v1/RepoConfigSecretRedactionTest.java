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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for {@code repo-config-secret}: before 2.2.9 the
 * repository and storage-alias READ endpoints returned the persisted config
 * JSONB verbatim, so a plain read grant disclosed upstream proxy passwords
 * ({@code remotes[].password}) and storage backend credentials
 * ({@code secretAccessKey}, tokens). The read API must present a redaction
 * boundary; secrets stay writable via PUT but are never echoed back, and a
 * client that round-trips the masked value must not clobber the stored
 * secret.
 *
 * @since 2.2.9
 */
public final class RepoConfigSecretRedactionTest extends AsyncApiTestBase {

    private static final String SECRET = "hunter2-upstream-password";

    private static final String ALIAS_SECRET = "AKIA-super-secret-access-key";

    @Test
    void repositoryReadDoesNotEchoTheRemotePassword(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final JsonObject body = new JsonObject().put("repo", new JsonObject()
            .put("type", "maven-proxy")
            .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
            .put("remotes", new JsonArray().add(new JsonObject()
                .put("url", "https://repo1.maven.org/maven2")
                .put("username", "bob")
                .put("password", SECRET))));
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/secret-repo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(body).toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat("fixture create", put.statusCode(), new IsEqual<>(200));
        final HttpResponse<Buffer> get = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/secret-repo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat("read must succeed", get.statusCode(), new IsEqual<>(200));
        MatcherAssert.assertThat(
            "the upstream password must never be echoed by the read endpoint",
            get.bodyAsString(), new IsNot<>(new StringContains(SECRET))
        );
        MatcherAssert.assertThat(
            "the non-secret username must still be present",
            get.bodyAsString(), new StringContains("bob")
        );
        ctx.completeNow();
    }

    @Test
    void roundTrippingTheMaskDoesNotClobberTheStoredSecret(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final JsonObject create = new JsonObject().put("repo", new JsonObject()
            .put("type", "maven-proxy")
            .put("storage", new JsonObject().put("type", "fs").put("path", "/tmp"))
            .put("remotes", new JsonArray().add(new JsonObject()
                .put("url", "https://repo1.maven.org/maven2")
                .put("username", "bob")
                .put("password", SECRET))));
        client.put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/mask-repo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(create).toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        // A UI-style edit: read the (masked) config back, change something
        // unrelated, and PUT it — the masked password must NOT overwrite the
        // real one that is still stored.
        final HttpResponse<Buffer> read = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/mask-repo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        final JsonObject masked = read.bodyAsJsonObject();
        masked.getJsonObject("repo").put("anonymous_read", false);
        final HttpResponse<Buffer> update = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/repositories/mask-repo")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(masked).toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat("masked round-trip update", update.statusCode(), new IsEqual<>(200));
        // Verify through the persistence layer, not the (redacted) read API —
        // the verticle is DB-backed in this harness, exactly like production.
        final javax.json.JsonObject stored = (javax.json.JsonObject)
            new com.auto1.pantera.db.dao.RepositoryDao(AsyncApiTestBase.sharedDs())
                .value(new com.auto1.pantera.api.RepositoryName.Simple("mask-repo"));
        final String storedPassword = stored.getJsonObject("repo")
            .getJsonArray("remotes").getJsonObject(0).getString("password");
        MatcherAssert.assertThat(
            "submitting the mask sentinel must preserve the stored secret, not overwrite it",
            storedPassword, new IsEqual<>(SECRET)
        );
        ctx.completeNow();
    }

    @Test
    void aliasListDoesNotEchoBackendCredentials(
        final Vertx vertx, final VertxTestContext ctx
    ) throws Exception {
        final WebClient client = WebClient.create(vertx);
        final JsonObject alias = new JsonObject()
            .put("type", "s3")
            .put("bucket", "b")
            .put("region", "eu-west-1")
            .put("credentials", new JsonObject()
                .put("type", "basic")
                .put("accessKeyId", "AKIAEXAMPLE")
                .put("secretAccessKey", ALIAS_SECRET));
        final HttpResponse<Buffer> put = client
            .put(this.port(), AsyncApiTestBase.HOST, "/api/v1/storages/leaky")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .sendJsonObject(alias).toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat("fixture alias create", put.statusCode(), new IsEqual<>(200));
        final HttpResponse<Buffer> list = client
            .get(this.port(), AsyncApiTestBase.HOST, "/api/v1/storages")
            .bearerTokenAuthentication(AsyncApiTestBase.TEST_TOKEN)
            .send().toCompletionStage().toCompletableFuture()
            .get(AsyncApiTestBase.TEST_TIMEOUT, TimeUnit.SECONDS);
        MatcherAssert.assertThat("alias list must succeed", list.statusCode(), new IsEqual<>(200));
        MatcherAssert.assertThat(
            "the backend secret access key must never be echoed by the alias list",
            list.bodyAsString(), new IsNot<>(new StringContains(ALIAS_SECRET))
        );
        ctx.completeNow();
    }
}
