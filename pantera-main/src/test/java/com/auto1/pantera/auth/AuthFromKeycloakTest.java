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
package com.auto1.pantera.auth;

import com.auto1.pantera.http.auth.AuthUser;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test for {@link AuthFromKeycloak} using Testcontainers with Keycloak.
 *
 * @since 0.28
 */
@DisabledOnOs(OS.WINDOWS)
@Testcontainers
final class AuthFromKeycloakTest {
    /**
     * Keycloak admin login.
     */
    private static final String ADMIN_LOGIN = "admin";

    /**
     * Keycloak admin password.
     */
    private static final String ADMIN_PASSWORD = "admin";

    /**
     * Keycloak realm.
     */
    private static final String REALM = "test_realm";

    /**
     * Keycloak client application id.
     */
    private static final String CLIENT_ID = "test_client";

    /**
     * Keycloak client application secret.
     */
    private static final String CLIENT_SECRET = "secret";

    /**
     * Test user username.
     */
    private static final String TEST_USER = "testuser";

    /**
     * Test user password.
     */
    private static final String TEST_PASSWORD = "testpass";

    /**
     * Keycloak container instance seeded with the test realm.
     */
    @Container
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.0.2")
        .withAdminUsername(ADMIN_LOGIN)
        .withAdminPassword(ADMIN_PASSWORD)
        .withStartupTimeout(Duration.ofMinutes(8))
        .withRealmImportFile("/test-realm.json");

    private Optional<AuthUser> authenticateWithRetry(final AuthFromKeycloak auth,
        final String username, final String password, final int attempts, final long delayMs) {
        Optional<AuthUser> res = Optional.empty();
        for (int attempt = 0; attempt < attempts; attempt++) {
            res = auth.user(username, password);
            if (res.isPresent()) {
                return res;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return res;
    }

    @Test
    void findsUser() {
        final AuthFromKeycloak auth = new AuthFromKeycloak(
            new org.keycloak.authorization.client.Configuration(
                KEYCLOAK.getAuthServerUrl(),
                REALM,
                CLIENT_ID,
                Map.of("secret", CLIENT_SECRET),
                null
            )
        );
        final Optional<AuthUser> user = authenticateWithRetry(auth, TEST_USER, TEST_PASSWORD, 10, 1_000L);
        MatcherAssert.assertThat(user.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(user.orElseThrow().name(), new IsEqual<>(TEST_USER));
    }

    @Test
    void doesNotFindUserWithWrongPassword() {
        final AuthFromKeycloak auth = new AuthFromKeycloak(
            new org.keycloak.authorization.client.Configuration(
                KEYCLOAK.getAuthServerUrl(),
                REALM,
                CLIENT_ID,
                Map.of("secret", CLIENT_SECRET),
                null
            )
        );
        final Optional<AuthUser> user = auth.user(TEST_USER, "wrongpassword");
        MatcherAssert.assertThat(user.isPresent(), new IsEqual<>(false));
    }

    @Test
    void doesNotFindNonExistentUser() {
        final AuthFromKeycloak auth = new AuthFromKeycloak(
            new org.keycloak.authorization.client.Configuration(
                KEYCLOAK.getAuthServerUrl(),
                REALM,
                CLIENT_ID,
                Map.of("secret", CLIENT_SECRET),
                null
            )
        );
        final Optional<AuthUser> user = auth.user("nonexistentuser", "password");
        MatcherAssert.assertThat(user.isPresent(), new IsEqual<>(false));
    }
}
