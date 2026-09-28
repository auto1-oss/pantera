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

import com.auto1.pantera.api.perms.ApiUserPermission;
import com.auto1.pantera.api.perms.ApiUserPermission.UserAction;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.auth.PasswordPolicy;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.settings.users.CrudUsers;
import com.auto1.pantera.test.TestPanteraCaches;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import java.security.AllPermission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObjectBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Exploit-regression tests for the user-management privilege ceiling:
 * <ul>
 *   <li>B13 — self-service {@code old_pass} was checked with the whole auth
 *       chain, which accepts the caller's own JWT as a password;</li>
 *   <li>B14 — {@code change_password} reset anyone's password (admins
 *       included) without {@code old_pass};</li>
 *   <li>B15 — an update-only manager could strip roles, flip the auth
 *       provider and plant {@code sso_subject} on privileged users;</li>
 *   <li>B48 — a weak password on PUT answered 500 after a partial write,
 *       and sending both {@code pass} and {@code password} bypassed the
 *       password policy.</li>
 * </ul>
 * Runs against an in-memory user store and policy (no database).
 *
 * @since 2.2.9
 */
@ExtendWith(VertxExtension.class)
final class UserHandlerCeilingTest {

    /**
     * A policy-compliant password.
     */
    private static final String GOOD = "Correct-Horse-Battery-9";

    /**
     * A JWT-shaped string (the auth chain would accept a real one as a password).
     */
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJtZ3IifQ.c2ln";

    private InMemoryUsers users;

    private HttpServer server;

    private WebClient client;

    @BeforeEach
    void setUp(final Vertx vertx) throws Exception {
        this.users = new InMemoryUsers();
        this.users.seed("admin", "admin", "Admin-Password-123");
        this.users.seed("mgr", "dev", "Manager-Password-123");
        this.users.seed("peer", "dev", "Peer-Password-1234");
        this.users.seed("ops", "ops", "Ops-Password-12345");
        this.users.seed("reader", "dev", "Reader-Password-12");
        final Policy<?> policy = user -> UserHandlerCeilingTest.perms(user.name());
        final PanteraSecurity security = new PanteraSecurity() {
            @Override
            public Authentication authentication() {
                // Like the production chain with JwtPasswordAuth: accepts a
                // token as the password. old_pass must never go through it.
                return (name, pass) -> Optional.of(new AuthUser(name, "local"));
            }

            @Override
            public Policy<?> policy() {
                return policy;
            }

            @Override
            public Optional<Storage> policyStorage() {
                return Optional.empty();
            }
        };
        final Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route().handler(ctx -> {
            ctx.setUser(User.create(new JsonObject()
                .put("sub", ctx.request().getHeader("X-Test-User"))
                .put("context", "local")));
            ctx.next();
        });
        new UserHandler(this.users, new TestPanteraCaches(), security).register(router);
        this.server = vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        this.client = WebClient.create(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void ownJwtIsNotTheCurrentPassword() throws Exception {
        MatcherAssert.assertThat(
            this.call("mgr", HttpMethod.POST, "/api/v1/users/mgr/password",
                new JsonObject().put("old_pass", JWT).put("new_pass", GOOD)).statusCode(),
            new IsEqual<>(403)
        );
    }

    @Test
    void selfServiceNeedsTheCurrentPasswordButNoGrant() throws Exception {
        MatcherAssert.assertThat(
            this.call("reader", HttpMethod.POST, "/api/v1/users/reader/password",
                new JsonObject().put("old_pass", "Reader-Password-12").put("new_pass", GOOD))
                .statusCode(),
            new IsEqual<>(200)
        );
    }

    @Test
    void changePasswordCannotResetAnAdministrator() throws Exception {
        MatcherAssert.assertThat(
            this.call("mgr", HttpMethod.POST, "/api/v1/users/admin/password",
                new JsonObject().put("new_pass", GOOD)).statusCode(),
            new IsEqual<>(403)
        );
    }

    @Test
    void changePasswordIsCappedAtTheCallersRoles() throws Exception {
        MatcherAssert.assertThat(
            "a user holding a role the caller lacks is out of reach",
            this.call("mgr", HttpMethod.POST, "/api/v1/users/ops/password",
                new JsonObject().put("new_pass", GOOD)).statusCode(),
            new IsEqual<>(403)
        );
        MatcherAssert.assertThat(
            "a user within the caller's roles can be reset",
            this.call("mgr", HttpMethod.POST, "/api/v1/users/peer/password",
                new JsonObject().put("new_pass", GOOD)).statusCode(),
            new IsEqual<>(200)
        );
    }

    @Test
    void resetOfAnotherUserNeedsTheGrant() throws Exception {
        MatcherAssert.assertThat(
            this.call("reader", HttpMethod.POST, "/api/v1/users/peer/password",
                new JsonObject().put("new_pass", GOOD)).statusCode(),
            new IsEqual<>(403)
        );
    }

    @Test
    void managerCannotStripAnAdministratorsRoles() throws Exception {
        MatcherAssert.assertThat(
            this.call("mgr", HttpMethod.PUT, "/api/v1/users/admin",
                new JsonObject().put("roles", new io.vertx.core.json.JsonArray())).statusCode(),
            new IsEqual<>(403)
        );
        MatcherAssert.assertThat(
            "the administrator keeps the admin role",
            this.users.roles("admin"), new IsEqual<>(java.util.List.of("admin"))
        );
    }

    @Test
    void managerCannotFlipTheAuthProvider() throws Exception {
        MatcherAssert.assertThat(
            this.call("mgr", HttpMethod.PUT, "/api/v1/users/peer",
                new JsonObject().put("type", "keycloak").put("email", "x@example.test"))
                .statusCode(),
            new IsEqual<>(403)
        );
    }

    @Test
    void ssoSubjectCannotBePlantedThroughTheApi() throws Exception {
        MatcherAssert.assertThat(
            "the rest of the update is applied",
            this.call("mgr", HttpMethod.PUT, "/api/v1/users/peer",
                new JsonObject().put("sso_subject", "forged").put("email", "p@example.test"))
                .statusCode(),
            new IsEqual<>(201)
        );
        MatcherAssert.assertThat(
            "sso_subject is only bound by the SSO login flow",
            this.users.get("peer").orElseThrow().containsKey("sso_subject"),
            new IsEqual<>(false)
        );
    }

    @Test
    void weakResetIsRefusedBeforeAnyWrite() throws Exception {
        final HttpResponse<Buffer> res = this.call("admin", HttpMethod.PUT, "/api/v1/users/peer",
            new JsonObject().put("password", "short").put("email", "changed@example.test"));
        MatcherAssert.assertThat("a weak password is a 400", res.statusCode(), new IsEqual<>(400));
        MatcherAssert.assertThat(
            "nothing was written",
            this.users.get("peer").orElseThrow().containsKey("email"), new IsEqual<>(false)
        );
    }

    @Test
    void conflictingPasswordFieldsAreRefused() throws Exception {
        MatcherAssert.assertThat(
            this.call("admin", HttpMethod.PUT, "/api/v1/users/peer",
                new JsonObject().put("pass", "a").put("password", GOOD)).statusCode(),
            new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "the old password still stands",
            this.users.passwordMatches("peer", "Peer-Password-1234"), new IsEqual<>(true)
        );
    }

    @Test
    void ownPasswordIsNotResetThroughPut() throws Exception {
        MatcherAssert.assertThat(
            this.call("mgr", HttpMethod.PUT, "/api/v1/users/mgr",
                new JsonObject().put("pass", GOOD)).statusCode(),
            new IsEqual<>(403)
        );
    }

    private HttpResponse<Buffer> call(final String caller, final HttpMethod method,
        final String path, final JsonObject body) throws Exception {
        return this.client.request(method, this.server.actualPort(), "localhost", path)
            .putHeader("X-Test-User", caller)
            .sendJsonObject(body)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static PermissionCollection perms(final String name) {
        final Permissions perms = new Permissions();
        if ("admin".equals(name)) {
            perms.add(new AllPermission());
        } else if ("mgr".equals(name)) {
            perms.add(new ApiUserPermission(UserAction.READ));
            perms.add(new ApiUserPermission(UserAction.CREATE));
            perms.add(new ApiUserPermission(UserAction.UPDATE));
            perms.add(new ApiUserPermission(UserAction.CHANGE_PASSWORD));
        } else {
            perms.add(new ApiUserPermission(UserAction.READ));
        }
        return perms;
    }

    /**
     * In-memory user store.
     */
    private static final class InMemoryUsers implements CrudUsers {

        private final Map<String, javax.json.JsonObject> docs = new ConcurrentHashMap<>();

        private final Map<String, String> passwords = new ConcurrentHashMap<>();

        void seed(final String name, final String role, final String pass) {
            this.docs.put(name, Json.createObjectBuilder()
                .add("name", name)
                .add("roles", Json.createArrayBuilder().add(role))
                .build());
            this.passwords.put(name, pass);
        }

        java.util.List<String> roles(final String name) {
            return this.docs.get(name).getJsonArray("roles")
                .getValuesAs(javax.json.JsonString.class).stream()
                .map(javax.json.JsonString::getString).toList();
        }

        @Override
        public JsonArray list() {
            return Json.createArrayBuilder().build();
        }

        @Override
        public Optional<javax.json.JsonObject> get(final String uname) {
            return Optional.ofNullable(this.docs.get(uname));
        }

        @Override
        public void addOrUpdate(final javax.json.JsonObject info, final String uname) {
            final JsonObjectBuilder merged = Json.createObjectBuilder(
                this.docs.getOrDefault(uname, Json.createObjectBuilder().build())
            );
            info.forEach((key, val) -> {
                if (!"pass".equals(key) && !"password".equals(key)) {
                    merged.add(key, val);
                }
            });
            if (info.containsKey("pass")) {
                this.passwords.put(uname, info.getString("pass"));
            }
            this.docs.put(uname, merged.build());
        }

        @Override
        public void disable(final String uname) {
            // not exercised
        }

        @Override
        public void enable(final String uname) {
            // not exercised
        }

        @Override
        public void remove(final String uname) {
            this.docs.remove(uname);
        }

        @Override
        public void alterPassword(final String uname, final javax.json.JsonObject info) {
            final String failure = PasswordPolicy.validate(uname, info.getString("new_pass"));
            if (failure != null) {
                throw new IllegalArgumentException(failure);
            }
            this.passwords.put(uname, info.getString("new_pass"));
        }

        @Override
        public boolean passwordMatches(final String uname, final String pass) {
            return pass != null && pass.equals(this.passwords.get(uname));
        }
    }
}
