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
import java.io.IOException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link AuthFromKeycloakFactory}.
 * @since 0.30
 */
class AuthFromKeycloakFactoryTest {

    @Test
    void initsKeycloak() throws IOException {
        MatcherAssert.assertThat(
            new AuthFromKeycloakFactory().getAuthentication(
                Yaml.createYamlInput(this.panteraKeycloakEnvCreds()).readYamlMapping()
            ),
            new IsInstanceOf(AuthFromKeycloak.class)
        );
    }

    private String panteraKeycloakEnvCreds() {
        return String.join(
            "\n",
            "credentials:",
            "  - type: env",
            "  - type: keycloak",
            "    url: http://any",
            "    realm: any",
            "    client-id: any",
            "    client-password: abc123",
            "  - type: local",
            "    storage:",
            "      type: fs",
            "      path: any"
        );
    }

}
