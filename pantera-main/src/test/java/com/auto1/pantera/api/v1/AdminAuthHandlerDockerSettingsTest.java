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

import com.auto1.pantera.adapters.docker.DockerTagRefreshSettingsLoader;
import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.test.InMemoryAuthSettings;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNull;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * HTTP contract of {@code GET}/{@code PUT /api/v1/admin/docker-settings},
 * against the real {@link AdminAuthHandler} routes over an in-memory
 * {@code auth_settings} table — no database, no Docker.
 *
 * @since 2.2.15
 */
@ExtendWith(VertxExtension.class)
@Timeout(60)
final class AdminAuthHandlerDockerSettingsTest {

    /**
     * Endpoint path.
     */
    private static final String PATH = "/api/v1/admin/docker-settings";

    /**
     * Settings table.
     */
    private InMemoryAuthSettings table;

    @BeforeEach
    void setUp() {
        this.table = new InMemoryAuthSettings();
    }

    @AfterEach
    void tearDown() {
        DockerTagRefreshSettingsLoader.uninstall();
    }

    @Test
    void getAnswersTheDefaultWithoutARow(final Vertx vertx) throws Exception {
        this.install();
        final HttpResponse<Buffer> res = AdminAuthHandlerDockerSettingsTest.call(
            vertx, this.serve(vertx, Policy.FREE), HttpMethod.GET, null
        );
        MatcherAssert.assertThat("GET answers 200", res.statusCode(), new IsEqual<>(200));
        MatcherAssert.assertThat(
            "the one field of the endpoint",
            res.bodyAsJsonObject().fieldNames(), new IsEqual<>(java.util.Set.of("tag_refresh_seconds"))
        );
        MatcherAssert.assertThat(
            "the default of one hour, as an integer",
            res.bodyAsJsonObject().getValue("tag_refresh_seconds"), new IsEqual<>(3600)
        );
    }

    @Test
    void putWritesTheRowAndTheNextGetReadsIt(final Vertx vertx) throws Exception {
        this.install();
        final int port = this.serve(vertx, Policy.FREE);
        final HttpResponse<Buffer> put = AdminAuthHandlerDockerSettingsTest.call(
            vertx, port, HttpMethod.PUT, new JsonObject().put("tag_refresh_seconds", 600)
        );
        MatcherAssert.assertThat("PUT answers 204", put.statusCode(), new IsEqual<>(204));
        MatcherAssert.assertThat(
            "the row is written under its auth_settings key",
            this.table.rows().get("docker_tag_refresh_seconds"), new IsEqual<>("600")
        );
        MatcherAssert.assertThat(
            "the loader is reloaded",
            DockerTagRefreshSettingsLoader.activeSupplier().get(),
            new IsEqual<>(Duration.ofSeconds(600))
        );
        MatcherAssert.assertThat(
            "GET answers the new value",
            AdminAuthHandlerDockerSettingsTest.call(vertx, port, HttpMethod.GET, null)
                .bodyAsJsonObject().getLong("tag_refresh_seconds"),
            new IsEqual<>(600L)
        );
    }

    @Test
    void putAcceptsZeroAndTheLargestAge(final Vertx vertx) throws Exception {
        this.install();
        final int port = this.serve(vertx, Policy.FREE);
        MatcherAssert.assertThat(
            "0 re-checks on every request",
            AdminAuthHandlerDockerSettingsTest.call(
                vertx, port, HttpMethod.PUT, new JsonObject().put("tag_refresh_seconds", 0)
            ).statusCode(),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "a year is the largest age",
            AdminAuthHandlerDockerSettingsTest.call(
                vertx, port, HttpMethod.PUT, new JsonObject().put("tag_refresh_seconds", 31_536_000)
            ).statusCode(),
            new IsEqual<>(204)
        );
    }

    @Test
    void putOutOfRangeIsRefusedAndWritesNothing(final Vertx vertx) throws Exception {
        this.install();
        final int port = this.serve(vertx, Policy.FREE);
        for (final Object value : new Object[] {-1, 31_536_001, 99_999_999_999L, 1.5, "600", true}) {
            final HttpResponse<Buffer> res = AdminAuthHandlerDockerSettingsTest.call(
                vertx, port, HttpMethod.PUT, new JsonObject().put("tag_refresh_seconds", value)
            );
            MatcherAssert.assertThat(
                String.format("%s is refused with 400", value),
                res.statusCode(), new IsEqual<>(400)
            );
            MatcherAssert.assertThat(
                String.format("the answer to %s names the field and its range", value),
                res.bodyAsJsonObject().getString("message"),
                new StringContains("tag_refresh_seconds must be an integer from 0 to 31536000")
            );
        }
        MatcherAssert.assertThat(
            "nothing is written",
            this.table.rows().get("docker_tag_refresh_seconds"), new IsNull<>()
        );
    }

    @Test
    void putWithAnUnknownKeyIsRefused(final Vertx vertx) throws Exception {
        this.install();
        final HttpResponse<Buffer> res = AdminAuthHandlerDockerSettingsTest.call(
            vertx, this.serve(vertx, Policy.FREE), HttpMethod.PUT,
            new JsonObject().put("tag_refresh_seconds", 600).put("docker_tag_refresh_seconds", 60)
        );
        MatcherAssert.assertThat("refused with 400", res.statusCode(), new IsEqual<>(400));
        MatcherAssert.assertThat(
            "the unknown key is named",
            res.bodyAsJsonObject().getString("message"),
            new IsEqual<>("Unknown docker setting: docker_tag_refresh_seconds")
        );
        MatcherAssert.assertThat(
            "nothing is written", this.table.rows().isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void putWithNullOrNoBodyIsRefused(final Vertx vertx) throws Exception {
        this.install();
        final int port = this.serve(vertx, Policy.FREE);
        MatcherAssert.assertThat(
            "a JSON null is refused",
            AdminAuthHandlerDockerSettingsTest.call(
                vertx, port, HttpMethod.PUT, new JsonObject().putNull("tag_refresh_seconds")
            ).statusCode(),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "an empty body is refused",
            AdminAuthHandlerDockerSettingsTest.call(
                vertx, port, HttpMethod.PUT, new JsonObject()
            ).statusCode(),
            new IsEqual<>(400)
        );
    }

    @Test
    void nonAdminIsRefused(final Vertx vertx) throws Exception {
        this.install();
        final Policy<PermissionCollection> none = user -> new Permissions();
        final int port = this.serve(vertx, none);
        MatcherAssert.assertThat(
            "GET is forbidden",
            AdminAuthHandlerDockerSettingsTest.call(vertx, port, HttpMethod.GET, null).statusCode(),
            new IsEqual<>(403)
        );
        MatcherAssert.assertThat(
            "PUT is forbidden",
            AdminAuthHandlerDockerSettingsTest.call(
                vertx, port, HttpMethod.PUT, new JsonObject().put("tag_refresh_seconds", 60)
            ).statusCode(),
            new IsEqual<>(403)
        );
        MatcherAssert.assertThat(
            "nothing is written", this.table.rows().isEmpty(), new IsEqual<>(true)
        );
    }

    /**
     * Install the loader over the in-memory table, as {@code VertxMain} does.
     */
    private void install() {
        DockerTagRefreshSettingsLoader.install(this.table.dao());
    }

    /**
     * Serve the admin routes behind a fake authenticated user.
     * @param vertx Vert.x
     * @param policy Policy of the user
     * @return Port
     * @throws Exception On failure
     */
    private int serve(final Vertx vertx, final Policy<?> policy) throws Exception {
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route().handler(
            ctx -> {
                ctx.setUser(
                    User.create(
                        new JsonObject()
                            .put(AuthTokenRest.SUB, "alice")
                            .put(AuthTokenRest.CONTEXT, "test")
                    )
                );
                ctx.next();
            }
        );
        new AdminAuthHandler(this.table.dao(), null, null, policy).register(router);
        final HttpServer server = vertx.createHttpServer().requestHandler(router)
            .listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return server.actualPort();
    }

    /**
     * Call the endpoint.
     * @param vertx Vert.x
     * @param port Port
     * @param method Method
     * @param body JSON body, or null for none
     * @return Response
     * @throws Exception On failure
     */
    private static HttpResponse<Buffer> call(
        final Vertx vertx, final int port, final HttpMethod method, final JsonObject body
    ) throws Exception {
        final HttpRequest<Buffer> req = WebClient.create(vertx)
            .request(method, port, "localhost", AdminAuthHandlerDockerSettingsTest.PATH);
        final var future = body == null ? req.send() : req.sendJsonObject(body);
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
