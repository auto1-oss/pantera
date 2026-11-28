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

import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.auth.failedlogins.GuardedPasswords;
import com.auto1.pantera.auth.failedlogins.InMemoryFailedLoginStore;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.keycloak.authorization.client.Configuration;

/**
 * A Keycloak that cannot be reached is an outage, not a wrong password:
 * the client is told to come back, and no failed login is counted.
 *
 * @since 2.2.15
 */
final class KeycloakOutageTest {

    @Test
    void unreachableKeycloakIsAnOutage() throws Exception {
        final AuthRefusal refusal = new PasswordCheck(KeycloakOutageTest.provider())
            .now("alice", "any password").refusal().orElseThrow();
        MatcherAssert.assertThat(
            "the reason names the identity provider",
            refusal.reason(), new IsEqual<>("identity_provider_unavailable")
        );
        MatcherAssert.assertThat(
            "the answer is 503",
            refusal.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
    }

    @Test
    void outageOfKeycloakLocksNobody() throws Exception {
        final FailedLogins logins = new FailedLogins(
            new InMemoryFailedLoginStore(), () -> new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false)
        );
        final Authentication guarded =
            new GuardedPasswords(KeycloakOutageTest.provider(), logins);
        for (int idx = 0; idx < 5; idx += 1) {
            new PasswordCheck(guarded).now("alice", "correct password");
        }
        MatcherAssert.assertThat(
            logins.state("alice").failures(), new IsEqual<>(0)
        );
    }

    /**
     * Keycloak provider that points at a port nothing listens on.
     * @return Provider
     * @throws Exception On error
     */
    private static AuthFromKeycloak provider() throws Exception {
        final int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        return new AuthFromKeycloak(
            new Configuration(
                "http://127.0.0.1:" + port, "realm", "client", Map.of("secret", "x"), null
            )
        );
    }
}
