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
import com.auto1.pantera.PanteraException;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link AuthFromOktaFactory} and {@link OktaGroupRoles}.
 */
final class AuthFromOktaFactoryTest {

    /**
     * Okta credentials entry.
     */
    private static final String OKTA = String.join(
        "\n",
        "credentials:",
        "  - type: okta",
        "    issuer: https://example.okta.com",
        "    client-id: id",
        "    client-secret: secret",
        "    group-roles:",
        "      - pantera_readers: reader",
        "      - pantera_admins: admin"
    );

    @Test
    void loadsWithADatabaseAndNoYamlPolicyStorage() throws IOException {
        MatcherAssert.assertThat(
            new AuthFromOktaFactory().getAuthentication(
                AuthFromOktaFactoryTest.yaml(AuthFromOktaFactoryTest.OKTA),
                // Never touched while the provider is built.
                (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[] {DataSource.class},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException(method.getName());
                    }
                )
            ),
            new IsInstanceOf(AuthFromOkta.class)
        );
    }

    @Test
    void loadsWithAYamlPolicyStorageAndNoDatabase(@TempDir final Path dir) throws IOException {
        final String policy = String.join(
            "\n",
            AuthFromOktaFactoryTest.OKTA,
            "policy:",
            "  type: local",
            "  storage:",
            "    type: fs",
            "    path: " + dir.toString().replace('\\', '/')
        );
        MatcherAssert.assertThat(
            new AuthFromOktaFactory().getAuthentication(AuthFromOktaFactoryTest.yaml(policy)),
            new IsInstanceOf(AuthFromOkta.class)
        );
    }

    @Test
    void saysWhatIsMissingWithNeitherBackend() throws IOException {
        final YamlMapping cfg = AuthFromOktaFactoryTest.yaml(AuthFromOktaFactoryTest.OKTA);
        final PanteraException err = Assertions.assertThrows(
            PanteraException.class,
            () -> new AuthFromOktaFactory().getAuthentication(cfg)
        );
        MatcherAssert.assertThat(
            err.getMessage(), new StringContains("meta.artifacts_database")
        );
    }

    @Test
    void readsGroupRolesAsAListOfMaps() throws IOException {
        MatcherAssert.assertThat(
            new OktaGroupRoles(
                AuthFromOktaFactoryTest.yaml(AuthFromOktaFactoryTest.OKTA)
                    .yamlSequence("credentials").yamlMapping(0)
            ).asMap(),
            new IsEqual<>(Map.of("pantera_readers", "reader", "pantera_admins", "admin"))
        );
    }

    @Test
    void readsGroupRolesAsOneMap() throws IOException {
        MatcherAssert.assertThat(
            new OktaGroupRoles(
                AuthFromOktaFactoryTest.yaml(
                    "group-roles:\n  pantera_readers: reader\n  pantera_admins: admin"
                )
            ).asMap(),
            new IsEqual<>(Map.of("pantera_readers", "reader", "pantera_admins", "admin"))
        );
    }

    private static YamlMapping yaml(final String text) throws IOException {
        return Yaml.createYamlInput(text).readYamlMapping();
    }
}
