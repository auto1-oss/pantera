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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.misc.UncheckedConsumer;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.security.policy.CachedYamlPolicy;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.test.TestPanteraCaches;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.eclipse.jetty.http.HttpStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.skyscreamer.jsonassert.JSONAssert;

/**
 * Test for {@link RolesRest}.
 */
@DisabledOnOs(OS.WINDOWS)
final class RolesRestTest extends RestApiServerBase {

    /**
     * Pantera authentication.
     * @return Authentication instance.
     */
    PanteraSecurity auth() {
        return new PanteraSecurity() {
            @Override
            public Authentication authentication() {
                return (name, pswd) -> Optional.of(new AuthUser("pantera", "test"));
            }

            @Override
            public Policy<?> policy() {
                final BlockingStorage asto = new BlockingStorage(RolesRestTest.super.ssto);
                asto.save(
                    new Key.From("users/pantera.yaml"),
                    String.join(
                        "\n",
                        "permissions:",
                        "  all_permission: {}"
                    ).getBytes(StandardCharsets.UTF_8)
                );
                return new CachedYamlPolicy(asto, 60_000L);
            }

            @Override
            public Optional<Storage> policyStorage() {
                return Optional.of(RolesRestTest.super.ssto);
            }
        };
    }

    @Test
    void listsRoles(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.saveIntoSecurityStorage(
            new Key.From("roles/java-dev.yaml"),
            String.join(
                System.lineSeparator(),
                "permissions:",
                "  adapter_basic_permissions:",
                "    maven:",
                "      - write",
                "      - read"
            ).getBytes(StandardCharsets.UTF_8)
        );
        this.saveIntoSecurityStorage(
            new Key.From("roles/readers.yml"),
            String.join(
                System.lineSeparator(),
                "permissions:",
                "  adapter_basic_permissions:",
                "    \"*\":",
                "      - read"
            ).getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest("/api/v1/roles"),
            new UncheckedConsumer<>(
                response -> JSONAssert.assertEquals(
                    response.body().toString(),
                    "[{\"name\":\"java-dev\",\"permissions\":{\"adapter_basic_permissions\":{\"maven\":[\"write\",\"read\"]}}},{\"name\":\"readers\",\"permissions\":{\"adapter_basic_permissions\":{\"*\":[\"read\"]}}}]",
                    false
                )
            )
        );
    }

    @Test
    void getsRole(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.saveIntoSecurityStorage(
            new Key.From("roles/java-dev.yaml"),
            String.join(
                System.lineSeparator(),
                "permissions:",
                "  adapter_basic_permissions:",
                "    maven:",
                "      - write",
                "      - read"
            ).getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest("/api/v1/roles/java-dev"),
            new UncheckedConsumer<>(
                response -> JSONAssert.assertEquals(
                    response.body().toString(),
                    "{\"name\":\"java-dev\",\"permissions\":{\"adapter_basic_permissions\":{\"maven\":[\"write\",\"read\"]}}}",
                    false
                )
            )
        );
    }

    @Test
    void returnsNotFoundIfRoleDoesNotExist(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.requestAndAssert(
            vertx, ctx, new TestRequest("/api/v1/roles/testers"),
            response -> MatcherAssert.assertThat(
                response.statusCode(),
                new IsEqual<>(HttpStatus.NOT_FOUND_404)
            )
        );
    }

    @Test
    void altersRole(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.saveIntoSecurityStorage(
            new Key.From("roles/testers.yaml"),
            String.join(
                System.lineSeparator(),
                "permissions:",
                "  adapter_basic_permissions:",
                "    test-repo:",
                "      - write",
                "      - read"
            ).getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest(
                HttpMethod.PUT, "/api/v1/roles/testers",
                new JsonObject().put(
                    "permissions", new JsonObject().put(
                        "adapter_basic_permissions",
                        new JsonObject().put("test-maven", JsonArray.of("read"))
                            .put("test-pypi", JsonArray.of("read", "write"))
                    )
                )
            ),
            response -> {
                MatcherAssert.assertThat(
                    response.statusCode(),
                    new IsEqual<>(HttpStatus.CREATED_201)
                );
                MatcherAssert.assertThat(
                    new String(
                        this.securityStorage().value(new Key.From("roles/testers.yaml")),
                        StandardCharsets.UTF_8
                    ),
                    new IsEqual<>(
                        String.join(
                            System.lineSeparator(),
                            "permissions:",
                            "  adapter_basic_permissions:",
                            "    \"test-maven\":",
                            "      - read",
                            "    \"test-pypi\":",
                            "      - read",
                            "      - write"
                        )
                    )
                );
            }
        );
    }

    @Test
    void addsRole(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.requestAndAssert(
            vertx, ctx, new TestRequest(
                HttpMethod.PUT, "/api/v1/roles/java-dev",
                new JsonObject().put(
                    "permissions",
                    new JsonObject().put(
                        "adapter_basic_permissions",
                        new JsonObject().put("maven-repo", JsonArray.of("read", "write"))
                    )
                )
            ),
            response -> {
                MatcherAssert.assertThat(
                    response.statusCode(),
                    new IsEqual<>(HttpStatus.CREATED_201)
                );
                MatcherAssert.assertThat(
                    new String(
                        this.securityStorage().value(new Key.From("roles/java-dev.yml")),
                        StandardCharsets.UTF_8
                    ),
                    new IsEqual<>(
                        String.join(
                            System.lineSeparator(),
                            "permissions:",
                            "  adapter_basic_permissions:",
                            "    \"maven-repo\":",
                            "      - read",
                            "      - write"
                        )
                    )
                );
                MatcherAssert.assertThat(
                    "Policy cache should be invalidated",
                    ((TestPanteraCaches) this.settingsCaches()).wasPolicyInvalidated()
                );
            }
        );
    }

    @Test
    void returnsNotFoundIfRoleDoesNotExistOnDelete(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.requestAndAssert(
            vertx, ctx, new TestRequest(HttpMethod.DELETE, "/api/v1/roles/any"),
            response -> MatcherAssert.assertThat(
                response.statusCode(),
                new IsEqual<>(HttpStatus.NOT_FOUND_404)
            )
        );
    }

    @Test
    void returnsNotFoundIfRoleDoesNotExistOnEnable(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.requestAndAssert(
            vertx, ctx, new TestRequest(HttpMethod.POST, "/api/v1/roles/tester/enable"),
            response -> MatcherAssert.assertThat(
                response.statusCode(),
                new IsEqual<>(HttpStatus.NOT_FOUND_404)
            )
        );
    }

    @Test
    void returnsNotFoundIfRoleDoesNotExistOnDisable(final Vertx vertx, final VertxTestContext ctx)
        throws Exception {
        this.requestAndAssert(
            vertx, ctx, new TestRequest(HttpMethod.POST, "/api/v1/roles/admin/disable"),
            response -> MatcherAssert.assertThat(
                response.statusCode(),
                new IsEqual<>(HttpStatus.NOT_FOUND_404)
            )
        );
    }

    @Test
    void removesRole(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.saveIntoSecurityStorage(
            new Key.From("roles/devs.yaml"),
            new byte[]{}
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest(HttpMethod.DELETE, "/api/v1/roles/devs"),
            response -> {
                MatcherAssert.assertThat(
                    response.statusCode(),
                    new IsEqual<>(HttpStatus.OK_200)
                );
                MatcherAssert.assertThat(
                    this.securityStorage().exists(new Key.From("roles/devs.yaml")),
                    new IsEqual<>(false)
                );
                MatcherAssert.assertThat(
                    "Policy cache should be invalidated",
                    ((TestPanteraCaches) this.settingsCaches()).wasPolicyInvalidated()
                );
            }
        );
    }

    @Test
    void enablesRole(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.saveIntoSecurityStorage(
            new Key.From("roles/java-dev.yml"),
            String.join(
                System.lineSeparator(),
                "enabled: false",
                "permissions:",
                "  adapter_basic_permissions:",
                "    \"maven-repo\":",
                "      - read",
                "      - write"
            ).getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest(HttpMethod.POST, "/api/v1/roles/java-dev/enable"),
            response -> {
                MatcherAssert.assertThat(
                    response.statusCode(),
                    new IsEqual<>(HttpStatus.OK_200)
                );
                MatcherAssert.assertThat(
                    new String(
                        this.securityStorage().value(new Key.From("roles/java-dev.yml")),
                        StandardCharsets.UTF_8
                    ),
                    new IsEqual<>(
                        String.join(
                            System.lineSeparator(),
                            "enabled: true",
                            "permissions:",
                            "  adapter_basic_permissions:",
                            "    \"maven-repo\":",
                            "      - read",
                            "      - write"
                        )
                    )
                );
                MatcherAssert.assertThat(
                    "Policy cache should be invalidated",
                    ((TestPanteraCaches) this.settingsCaches()).wasPolicyInvalidated()
                );
            }
        );
    }

    @Test
    void disablesRole(final Vertx vertx, final VertxTestContext ctx) throws Exception {
        this.saveIntoSecurityStorage(
            new Key.From("roles/java-dev.yml"),
            String.join(
                System.lineSeparator(),
                "permissions:",
                "  adapter_basic_permissions:",
                "    \"maven-repo\":",
                "      - read",
                "      - write"
            ).getBytes(StandardCharsets.UTF_8)
        );
        this.requestAndAssert(
            vertx, ctx, new TestRequest(HttpMethod.POST, "/api/v1/roles/java-dev/disable"),
            response -> {
                MatcherAssert.assertThat(
                    response.statusCode(),
                    new IsEqual<>(HttpStatus.OK_200)
                );
                MatcherAssert.assertThat(
                    new String(
                        this.securityStorage().value(new Key.From("roles/java-dev.yml")),
                        StandardCharsets.UTF_8
                    ),
                    new IsEqual<>(
                        String.join(
                            System.lineSeparator(),
                            "permissions:",
                            "  adapter_basic_permissions:",
                            "    \"maven-repo\":",
                            "      - read",
                            "      - write",
                            "enabled: false"
                        )
                    )
                );
                MatcherAssert.assertThat(
                    "Policy cache should be invalidated",
                    ((TestPanteraCaches) this.settingsCaches()).wasPolicyInvalidated()
                );
            }
        );
    }

}
