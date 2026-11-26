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
package com.auto1.pantera.settings.policy;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.scheduling.QuartzService;
import com.auto1.pantera.settings.YamlSettings;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sign-in chain that {@link YamlSettings} builds asks an identity
 * provider for a password only where the password sign-in policy allows.
 * The provider is a Keycloak nothing listens on: asked, it refuses the
 * login as an outage; not asked, the login is a plain failed login.
 *
 * @since 2.2.15
 */
final class PasswordSignInChainTest {

    /**
     * A login that comes with a repository request.
     */
    private static final LoginSource REPOSITORY =
        new LoginSource("10.0.0.1", "Apache-Maven/3.9", LoginSource.REPOSITORY);

    /**
     * A login from the sign-in form.
     */
    private static final LoginSource FORM = new LoginSource("10.0.0.1", "Mozilla/5.0", "ui");

    /**
     * Reason of a login the provider was asked for.
     */
    private static final Optional<String> ASKED = Optional.of("identity_provider_unavailable");

    /**
     * Repositories directory.
     */
    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        PasswordSignInSettingsLoader.uninstall();
        FailedLoginsSettingsLoader.uninstall();
    }

    @Test
    void byDefaultARepositoryLoginReachesTheProvider() throws Exception {
        MatcherAssert.assertThat(
            PasswordSignInChainTest.reason(this.chain(), PasswordSignInChainTest.REPOSITORY),
            new IsEqual<>(PasswordSignInChainTest.ASKED)
        );
    }

    @Test
    void formOnlyKeepsRepositoryLoginsFromTheProvider() throws Exception {
        PasswordSignInSettingsLoader.install(
            FakeAuthSettings.withRows(Map.of("password_sign_in_keycloak", "form")),
            name -> null
        );
        final Authentication chain = this.chain();
        MatcherAssert.assertThat(
            "a repository login is a plain failed login: nobody was asked",
            PasswordSignInChainTest.reason(chain, PasswordSignInChainTest.REPOSITORY),
            new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "a login from the form reaches the provider",
            PasswordSignInChainTest.reason(chain, PasswordSignInChainTest.FORM),
            new IsEqual<>(PasswordSignInChainTest.ASKED)
        );
    }

    @Test
    void offKeepsEveryLoginFromTheProvider() throws Exception {
        PasswordSignInSettingsLoader.install(
            FakeAuthSettings.withRows(Map.of("password_sign_in_keycloak", "off")),
            name -> null
        );
        final Authentication chain = this.chain();
        MatcherAssert.assertThat(
            "a login from the form",
            PasswordSignInChainTest.reason(chain, PasswordSignInChainTest.FORM),
            new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "and a repository login",
            PasswordSignInChainTest.reason(chain, PasswordSignInChainTest.REPOSITORY),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void changedSettingAppliesToTheRunningChain() throws Exception {
        final Authentication chain = this.chain();
        PasswordSignInSettingsLoader.install(
            FakeAuthSettings.withRows(Map.of("password_sign_in_keycloak", "off")),
            name -> null
        );
        MatcherAssert.assertThat(
            PasswordSignInChainTest.reason(chain, PasswordSignInChainTest.REPOSITORY),
            new IsEqual<>(Optional.empty())
        );
    }

    private static Optional<String> reason(final Authentication chain, final LoginSource source) {
        return new PasswordCheck(chain, source).now("alice", "any password")
            .refusal().map(AuthRefusal::reason);
    }

    private Authentication chain() throws Exception {
        // No delay and no lock: every login of the test is checked.
        FailedLoginsSettingsLoader.install(
            FakeAuthSettings.withRows(
                Map.of("failed_logins_delay_from_failure", "0", "failed_logins_lock_after", "0")
            ),
            name -> null
        );
        final int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        return new YamlSettings(
            Yaml.createYamlInput(
                String.join(
                    "\n",
                    "meta:",
                    "  credentials:",
                    "    - type: keycloak",
                    "      url: http://127.0.0.1:" + port,
                    "      realm: realm",
                    "      client-id: client",
                    "      client-password: x"
                )
            ).readYamlMapping(),
            this.temp, new QuartzService()
        ).authz().authentication();
    }
}
