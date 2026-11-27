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
import com.auto1.pantera.auth.signin.InMemorySignInFailureStore;
import com.auto1.pantera.auth.signin.RejectedKey;
import com.auto1.pantera.auth.signin.SignInFailures;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.security.perms.EmptyPermissions;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import com.auto1.pantera.settings.policy.SignInReportConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The sign-in failures report over the REST API.
 *
 * @since 2.2.15
 */
@ExtendWith(VertxExtension.class)
final class SignInFailuresHandlerTest {

    /**
     * A build tool.
     */
    private static final LoginSource MAVEN =
        new LoginSource("192.0.2.7", "Apache-Maven/3.9.9", LoginSource.REPOSITORY);

    /**
     * An IDE on another machine.
     */
    private static final LoginSource IDE =
        new LoginSource("198.51.100.9", "IntelliJ IDEA/2026.2", LoginSource.REPOSITORY);

    /**
     * Failed logins of the installation.
     */
    private FailedLogins logins;

    /**
     * The report.
     */
    private SignInFailures report;

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
        this.report = new SignInFailures(
            new InMemorySignInFailureStore(), SignInReportConfig::defaults
        );
        this.logins.reportTo(this.report);
        this.start(Policy.FREE, "admin");
    }

    @AfterEach
    void tearDown() throws Exception {
        this.stop();
    }

    @Test
    void rejectedKeysAreListedWithOwnerKeyNameReasonAndOrigin() throws Exception {
        for (int idx = 0; idx < 3; idx += 1) {
            this.report.rejectedKey(
                new RejectedKey("api_key_expired", "alice", "key-1", true),
                SignInFailuresHandlerTest.MAVEN
            );
        }
        this.report.rejectedKey(
            new RejectedKey("api_key_unknown", "bob", "", false), SignInFailuresHandlerTest.IDE
        );
        final JsonObject body = this.get("/api/v1/sign-in-failures/api-keys");
        MatcherAssert.assertThat("two lines", body.getInteger("total"), new IsEqual<>(2));
        final JsonObject alice = SignInFailuresHandlerTest.of(body, "alice");
        MatcherAssert.assertThat(
            "the owner's line names the key, why and how often it was rejected, and from where",
            List.of(
                alice.getString("key_id"), alice.getString("key_name"),
                alice.getString("key_expires_at"), alice.getString("reason"),
                String.valueOf(alice.getLong("rejected")), alice.getString("client_ip"),
                alice.getString("user_agent"), alice.getString("path"),
                String.valueOf(alice.getBoolean("verified"))
            ),
            new IsEqual<>(
                List.of(
                    "key-1", "CI token", "2026-01-01T00:00:00Z", "api_key_expired", "3",
                    "192.0.2.7", "Apache-Maven/3.9.9", "repository", "true"
                )
            )
        );
        final JsonObject bob = SignInFailuresHandlerTest.of(body, "bob");
        MatcherAssert.assertThat(
            "a key that proves no owner is marked as unverified and has no name",
            List.of(String.valueOf(bob.getBoolean("verified")), String.valueOf(bob.getString("key_name"))),
            new IsEqual<>(List.of("false", "null"))
        );
    }

    @Test
    void listsAreFilteredByUserAndClientAddressAndPaged() throws Exception {
        for (int idx = 0; idx < 5; idx += 1) {
            this.report.failedPassword("user-" + idx, SignInFailuresHandlerTest.MAVEN);
        }
        this.report.failedPassword("someone", SignInFailuresHandlerTest.IDE);
        MatcherAssert.assertThat(
            "by a part of the user name",
            this.get("/api/v1/sign-in-failures/passwords?user=USER-").getInteger("total"),
            new IsEqual<>(5)
        );
        MatcherAssert.assertThat(
            "by a part of the client address",
            this.get("/api/v1/sign-in-failures/passwords?client_ip=198.51.").getInteger("total"),
            new IsEqual<>(1)
        );
        final JsonObject page = this.get("/api/v1/sign-in-failures/passwords?page=1&size=2");
        MatcherAssert.assertThat(
            "a page has its size and says that more follow",
            List.of(
                page.getJsonArray("items").size(), page.getInteger("total"),
                page.getBoolean("hasMore") ? 1 : 0
            ),
            new IsEqual<>(List.of(2, 6, 1))
        );
    }

    @Test
    void failedPasswordLoginsCarryTheStateOfTheUser() throws Exception {
        final LoginOrigin origin =
            new LoginOrigin("192.0.2.7", "Apache-Maven/3.9.9", "repository", "password");
        for (int idx = 0; idx < 3; idx += 1) {
            this.logins.failed("alice", origin);
        }
        this.logins.failed("bob", origin);
        final JsonObject body = this.get("/api/v1/sign-in-failures/passwords");
        final JsonObject alice = SignInFailuresHandlerTest.of(body, "alice");
        MatcherAssert.assertThat(
            "three failed logins from that address, and the user is locked now",
            List.of(
                String.valueOf(alice.getLong("failed")), alice.getString("client_ip"),
                String.valueOf(alice.getJsonObject("state").getBoolean("locked")),
                String.valueOf(alice.getJsonObject("state").getInteger("failures"))
            ),
            new IsEqual<>(List.of("3", "192.0.2.7", "true", "3"))
        );
        MatcherAssert.assertThat(
            "a user with one failure is not locked",
            SignInFailuresHandlerTest.of(body, "bob").getJsonObject("state").getBoolean("locked"),
            new IsEqual<>(false)
        );
        this.logins.unlock("alice", "admin");
        final JsonObject state = SignInFailuresHandlerTest
            .of(this.get("/api/v1/sign-in-failures/passwords"), "alice").getJsonObject("state");
        MatcherAssert.assertThat(
            "after the unlock the line says who unlocked",
            List.of(
                String.valueOf(state.getBoolean("locked")), state.getString("unlocked_by"),
                String.valueOf(state.getString("unlocked_at") != null)
            ),
            new IsEqual<>(List.of("false", "admin", "true"))
        );
    }

    @Test
    void unconfirmedSecondFactorsAreListedWithThePause() throws Exception {
        final LoginSource form = new LoginSource("192.0.2.9", "Mozilla/5.0", "ui");
        final java.time.Instant end = java.time.Instant.now().plusSeconds(857L);
        this.report.unconfirmedSecondFactor("alice", form, 5, end);
        this.report.refusedPaused("alice", form, 5, end);
        final JsonObject item = this.get("/api/v1/sign-in-failures/second-factor")
            .getJsonArray("items").getJsonObject(0);
        MatcherAssert.assertThat(
            "user, address, unconfirmed, refused, count, and the pause is still on",
            List.of(
                item.getString("user"), item.getString("client_ip"),
                String.valueOf(item.getLong("unconfirmed")),
                String.valueOf(item.getLong("refused")),
                String.valueOf(item.getInteger("counted")),
                String.valueOf(item.getBoolean("paused")),
                String.valueOf(item.getString("paused_until") != null)
            ),
            new IsEqual<>(List.of("alice", "192.0.2.9", "1", "1", "5", "true", "true"))
        );
    }

    @Test
    void everyUserSeesTheirOwnRejectedKeysAndNoOthers() throws Exception {
        this.report.rejectedKey(
            new RejectedKey("api_key_revoked", "alice", "key-1", true),
            SignInFailuresHandlerTest.MAVEN
        );
        this.report.rejectedKey(
            new RejectedKey("api_key_expired", "alice2", "key-9", true),
            SignInFailuresHandlerTest.MAVEN
        );
        this.stop();
        this.start(user -> EmptyPermissions.INSTANCE, "alice");
        final JsonObject own = this.get("/api/v1/auth/rejected-keys");
        MatcherAssert.assertThat(
            "the user's own key, by the whole name",
            own.getJsonArray("items").stream()
                .map(item -> ((JsonObject) item).getString("key_id")).toList(),
            new IsEqual<>(List.of("key-1"))
        );
        MatcherAssert.assertThat(
            "the lists of all users are for administrators",
            List.of(
                this.call("/api/v1/sign-in-failures/api-keys").statusCode(),
                this.call("/api/v1/sign-in-failures/passwords").statusCode(),
                this.call("/api/v1/sign-in-failures/second-factor").statusCode()
            ),
            new IsEqual<>(List.of(403, 403, 403))
        );
    }

    private static JsonObject of(final JsonObject body, final String user) {
        final JsonArray items = body.getJsonArray("items");
        for (int idx = 0; idx < items.size(); idx += 1) {
            if (user.equals(items.getJsonObject(idx).getString("user"))) {
                return items.getJsonObject(idx);
            }
        }
        throw new AssertionError("no line of " + user + " in " + body.encode());
    }

    private void start(final Policy<?> policy, final String caller) throws Exception {
        final Router router = Router.router(this.vertx);
        router.route().handler(
            ctx -> {
                ctx.setUser(
                    User.create(new JsonObject().put("sub", caller).put("context", "test"))
                );
                ctx.next();
            }
        );
        new SignInFailuresHandler(
            this.report, this.logins,
            ids -> {
                final Map<String, SignInFailuresHandler.KeyName> names = new HashMap<>();
                if (ids.contains("key-1")) {
                    names.put(
                        "key-1",
                        new SignInFailuresHandler.KeyName(
                            "CI token", Instant.parse("2025-01-01T00:00:00Z"),
                            Instant.parse("2026-01-01T00:00:00Z")
                        )
                    );
                }
                return names;
            },
            policy
        ).register(router);
        this.server = this.vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        this.client = WebClient.create(this.vertx);
    }

    private void stop() throws Exception {
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private JsonObject get(final String path) throws Exception {
        return this.call(path).bodyAsJsonObject();
    }

    private HttpResponse<Buffer> call(final String path) throws Exception {
        return this.client.get(this.server.actualPort(), "127.0.0.1", path)
            .send().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
