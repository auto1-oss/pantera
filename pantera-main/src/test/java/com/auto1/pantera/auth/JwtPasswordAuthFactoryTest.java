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

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.auth.AuthLoader;
import com.auto1.pantera.http.auth.Authentication;
import io.vertx.core.Vertx;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Tests for {@link JwtPasswordAuthFactory}.
 *
 * @since 1.20.7
 */
class JwtPasswordAuthFactoryTest {

    /**
     * Shared Vertx instance.
     */
    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
        JwtPasswordAuthFactory.setSharedVertx(vertx);
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    @Test
    void createsJwtPasswordAuthFromConfig() throws IOException {
        final YamlMapping config = Yaml.createYamlInput(
            String.join(
                "\n",
                "meta:",
                "  jwt:",
                "    secret: test-secret-key",
                "    expires: true",
                "    expiry-seconds: 3600",
                "  credentials:",
                "    - type: jwt-password"
            )
        ).readYamlMapping();
        final JwtPasswordAuthFactory factory = new JwtPasswordAuthFactory();
        final Authentication auth = factory.getAuthentication(config);
        MatcherAssert.assertThat(
            "Factory should create JwtPasswordAuth instance",
            auth,
            new IsInstanceOf(JwtPasswordAuth.class)
        );
    }

    @Test
    void createsJwtPasswordAuthWithDefaultSecret() throws IOException {
        final YamlMapping config = Yaml.createYamlInput(
            String.join(
                "\n",
                "meta:",
                "  credentials:",
                "    - type: jwt-password"
            )
        ).readYamlMapping();
        final JwtPasswordAuthFactory factory = new JwtPasswordAuthFactory();
        final Authentication auth = factory.getAuthentication(config);
        MatcherAssert.assertThat(
            "Factory should create JwtPasswordAuth even without explicit jwt config",
            auth,
            new IsInstanceOf(JwtPasswordAuth.class)
        );
    }

    @Test
    void factoryIsRegisteredWithAuthLoader() throws IOException {
        // Verify the factory can be loaded by AuthLoader
        final AuthLoader loader = new AuthLoader();
        final YamlMapping config = Yaml.createYamlInput(
            String.join(
                "\n",
                "meta:",
                "  jwt:",
                "    secret: test-secret-key",
                "  credentials:",
                "    - type: jwt-password"
            )
        ).readYamlMapping();
        // This will throw if jwt-password is not registered
        final Authentication auth = loader.newObject("jwt-password", config);
        MatcherAssert.assertThat(
            "AuthLoader should create JwtPasswordAuth from 'jwt-password' type",
            auth,
            new IsInstanceOf(JwtPasswordAuth.class)
        );
    }

    @Test
    void createsAuthWithUsernameMatchDisabled() throws IOException {
        final YamlMapping config = Yaml.createYamlInput(
            String.join(
                "\n",
                "meta:",
                "  jwt:",
                "    secret: test-secret-key",
                "  jwt-password:",
                "    require-username-match: false",
                "  credentials:",
                "    - type: jwt-password"
            )
        ).readYamlMapping();
        final JwtPasswordAuthFactory factory = new JwtPasswordAuthFactory();
        final Authentication auth = factory.getAuthentication(config);
        MatcherAssert.assertThat(
            "Factory should create JwtPasswordAuth with username match disabled",
            auth.toString(),
            Matchers.containsString("requireUsernameMatch=false")
        );
    }
}
