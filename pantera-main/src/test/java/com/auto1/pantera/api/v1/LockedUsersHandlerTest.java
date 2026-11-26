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

import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.auth.failedlogins.InMemoryFailedLoginStore;
import com.auto1.pantera.auth.failedlogins.LoginOrigin;
import com.auto1.pantera.security.perms.EmptyPermissions;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests for {@link LockedUsersHandler}: an admin sees who is locked and
 * why, and unlocks one user or all.
 *
 * @since 2.2.15
 */
@ExtendWith(VertxExtension.class)
final class LockedUsersHandlerTest {

    /**
     * Where the test failures come from.
     */
    private static final LoginOrigin ORIGIN =
        new LoginOrigin("192.0.2.7", "Apache-Maven/3.9.9", "repository", "password");

    /**
     * Failed logins of the installation.
     */
    private FailedLogins logins;

    /**
     * Vert.x of the test.
     */
    private Vertx vertx;

    /**
     * API server.
     */
    private HttpServer server;

    /**
     * API client.
     */
    private WebClient client;

    @BeforeEach
    void setUp(final Vertx vrtx) throws Exception {
        this.vertx = vrtx;
        this.logins = new FailedLogins(
            new InMemoryFailedLoginStore(), () -> new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false)
        );
        this.start(Policy.FREE);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.stop();
    }

    @Test
    void listsLockedNamesWithWhereTheLastFailureCameFrom() throws Exception {
        this.lock("alice@example.com");
        this.logins.failed("bob", LockedUsersHandlerTest.ORIGIN);
        final JsonObject body = this.call(HttpMethod.GET, "/api/v1/locked-users").bodyAsJsonObject();
        MatcherAssert.assertThat("one name is locked", body.getInteger("total"), new IsEqual<>(1));
        final JsonObject item = body.getJsonArray("items").getJsonObject(0);
        MatcherAssert.assertThat(
            "name", item.getString("name"), new IsEqual<>("alice@example.com")
        );
        MatcherAssert.assertThat("count", item.getInteger("failures"), new IsEqual<>(3));
        MatcherAssert.assertThat(
            "client address", item.getString("client_ip"), new IsEqual<>("192.0.2.7")
        );
        MatcherAssert.assertThat(
            "client program", item.getString("user_agent"), new IsEqual<>("Apache-Maven/3.9.9")
        );
        MatcherAssert.assertThat("path", item.getString("path"), new IsEqual<>("repository"));
        MatcherAssert.assertThat(
            "the time of the lock is given", item.getString("locked_at") == null,
            new IsEqual<>(false)
        );
    }

    @Test
    void detailsOfANameSayWhetherItIsLocked() throws Exception {
        this.lock("alice@example.com");
        this.logins.failed("bob", LockedUsersHandlerTest.ORIGIN);
        final JsonObject alice = this.call(
            HttpMethod.GET, "/api/v1/users/alice%40example.com/failed-logins"
        ).bodyAsJsonObject();
        final JsonObject bob =
            this.call(HttpMethod.GET, "/api/v1/users/bob/failed-logins").bodyAsJsonObject();
        final JsonObject carol =
            this.call(HttpMethod.GET, "/api/v1/users/carol/failed-logins").bodyAsJsonObject();
        MatcherAssert.assertThat(
            "locked, counted and unknown names",
            List.of(
                alice.getBoolean("locked"), alice.getInteger("failures"),
                bob.getBoolean("locked"), bob.getInteger("failures"),
                carol.getBoolean("locked"), carol.getInteger("failures")
            ),
            new IsEqual<>(List.of(true, 3, false, 1, false, 0))
        );
        MatcherAssert.assertThat(
            "the last failure is described",
            bob.getString("client_ip"), new IsEqual<>("192.0.2.7")
        );
    }

    @Test
    void unlockRemovesTheLockOfOneName() throws Exception {
        this.lock("alice@example.com");
        this.lock("bob");
        final JsonObject body = this.call(
            HttpMethod.POST, "/api/v1/users/alice%40example.com/unlock"
        ).bodyAsJsonObject();
        MatcherAssert.assertThat("a lock was removed", body.getBoolean("unlocked"), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "alice is free, bob is not",
            List.of(this.logins.locked("alice@example.com"), this.logins.locked("bob")),
            new IsEqual<>(List.of(false, true))
        );
    }

    @Test
    void unlockOfANameThatIsNotLockedSaysSo() throws Exception {
        final HttpResponse<Buffer> resp = this.call(HttpMethod.POST, "/api/v1/users/bob/unlock");
        MatcherAssert.assertThat("status", resp.statusCode(), new IsEqual<>(200));
        MatcherAssert.assertThat(
            "no lock was removed",
            resp.bodyAsJsonObject().getBoolean("unlocked"), new IsEqual<>(false)
        );
    }

    @Test
    void unlockAllRemovesEveryLock() throws Exception {
        this.lock("alice@example.com");
        this.lock("bob");
        final JsonObject body =
            this.call(HttpMethod.POST, "/api/v1/users/unlock-all").bodyAsJsonObject();
        MatcherAssert.assertThat("both are named", body.getInteger("count"), new IsEqual<>(2));
        MatcherAssert.assertThat(
            "nobody is locked", this.logins.lockedNames().size(), new IsEqual<>(0)
        );
    }

    @Test
    void userWithoutThePermissionCanNeitherSeeNorUnlock() throws Exception {
        this.lock("alice@example.com");
        this.stop();
        this.start(user -> EmptyPermissions.INSTANCE);
        MatcherAssert.assertThat(
            "list, details, unlock and unlock-all are refused",
            List.of(
                this.call(HttpMethod.GET, "/api/v1/locked-users").statusCode(),
                this.call(HttpMethod.GET, "/api/v1/users/bob/failed-logins").statusCode(),
                this.call(HttpMethod.POST, "/api/v1/users/alice%40example.com/unlock").statusCode(),
                this.call(HttpMethod.POST, "/api/v1/users/unlock-all").statusCode()
            ),
            new IsEqual<>(List.of(403, 403, 403, 403))
        );
        MatcherAssert.assertThat(
            "and the lock stays", this.logins.locked("alice@example.com"), new IsEqual<>(true)
        );
    }

    private void lock(final String name) {
        for (int idx = 0; idx < 3; idx += 1) {
            this.logins.failed(name, LockedUsersHandlerTest.ORIGIN);
        }
    }

    private void start(final Policy<?> policy) throws Exception {
        final Router router = Router.router(this.vertx);
        router.route().handler(
            ctx -> {
                ctx.setUser(
                    User.create(new JsonObject().put("sub", "admin").put("context", "test"))
                );
                ctx.next();
            }
        );
        new LockedUsersHandler(this.logins, policy).register(router);
        this.server = this.vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        this.client = WebClient.create(this.vertx);
    }

    private void stop() throws Exception {
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private HttpResponse<Buffer> call(final HttpMethod method, final String path) throws Exception {
        return this.client.request(method, this.server.actualPort(), "127.0.0.1", path)
            .send().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
