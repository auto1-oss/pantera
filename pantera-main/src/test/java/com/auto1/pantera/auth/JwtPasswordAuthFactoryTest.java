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

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

/**
 * Tests for {@link JwtPasswordAuthFactory}.
 *
 * <p>Asserts the factory wires an RS256 {@link JwtPasswordAuth} from
 * {@code meta.jwt.public-key-path} and fails fast on the legacy HS256
 * {@code secret} shape. The fixture PEMs under {@code auth/rsa/} are
 * the same key pair {@link RsaKeyLoaderTest} and {@link JwtPasswordAuthTest}
 * use — so all three tests exercise a single, consistent RS256 story.
 *
 * @since 1.20.7
 */
class JwtPasswordAuthFactoryTest {

    private static final String FIXTURES = "auth/rsa/";

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
    void createsJwtPasswordAuthFromRs256Config() throws IOException {
        final YamlMapping config = configWithRsaKeys();
        final Authentication auth = new JwtPasswordAuthFactory().getAuthentication(config);
        MatcherAssert.assertThat(auth, new IsInstanceOf(JwtPasswordAuth.class));
    }

    @Test
    void failsFastWhenPublicKeyPathIsMissing() throws IOException {
        final YamlMapping config = Yaml.createYamlInput(
            String.join(
                "\n",
                "meta:",
                "  credentials:",
                "    - type: jwt-password"
            )
        ).readYamlMapping();
        final JwtPasswordAuthFactory factory = new JwtPasswordAuthFactory();
        final IllegalStateException ex = Assertions.assertThrows(
            IllegalStateException.class,
            () -> factory.getAuthentication(config)
        );
        MatcherAssert.assertThat(
            ex.getMessage(),
            Matchers.containsString("public-key-path is not configured")
        );
    }

    @Test
    void legacyHs256SecretConfigIsRejectedAtStartup() throws IOException {
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
        final JwtPasswordAuthFactory factory = new JwtPasswordAuthFactory();
        final IllegalStateException ex = Assertions.assertThrows(
            IllegalStateException.class,
            () -> factory.getAuthentication(config)
        );
        MatcherAssert.assertThat(
            "Pre-2.1.0 HS256 config must fail loud, not silently",
            ex.getMessage(),
            Matchers.containsString("HS256 secret configuration is no longer supported")
        );
    }

    @Test
    void factoryIsRegisteredWithAuthLoader() throws IOException {
        final AuthLoader loader = new AuthLoader();
        final Authentication auth = loader.newObject("jwt-password", configWithRsaKeys());
        MatcherAssert.assertThat(auth, new IsInstanceOf(JwtPasswordAuth.class));
    }

    @Test
    void createsAuthWithUsernameMatchDisabled() throws IOException {
        final YamlMapping config = Yaml.createYamlInput(
            String.join(
                "\n",
                "meta:",
                "  jwt:",
                "    private-key-path: " + resourcePath("priv-2048-pkcs8.pem"),
                "    public-key-path: " + resourcePath("pub-2048.pem"),
                "  jwt-password:",
                "    require-username-match: false",
                "  credentials:",
                "    - type: jwt-password"
            )
        ).readYamlMapping();
        final Authentication auth = new JwtPasswordAuthFactory().getAuthentication(config);
        MatcherAssert.assertThat(
            auth.toString(),
            Matchers.containsString("requireUsernameMatch=false")
        );
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static YamlMapping configWithRsaKeys() throws IOException {
        return Yaml.createYamlInput(
            String.join(
                "\n",
                "meta:",
                "  jwt:",
                "    private-key-path: " + resourcePath("priv-2048-pkcs8.pem"),
                "    public-key-path: " + resourcePath("pub-2048.pem"),
                "  credentials:",
                "    - type: jwt-password"
            )
        ).readYamlMapping();
    }

    private static String resourcePath(final String resource) {
        final URL url = JwtPasswordAuthFactoryTest.class.getClassLoader()
            .getResource(FIXTURES + resource);
        if (url == null) {
            throw new IllegalStateException("Missing test fixture: " + FIXTURES + resource);
        }
        return Path.of(URI.create(url.toString())).toString();
    }
}
