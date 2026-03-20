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

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.api.ssl.KeyStore;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.test.TestSettings;
import java.io.IOException;
import java.util.Optional;

/**
 * SSL test for JKS.
 * @since 0.26
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TestClassWithoutTestCases"})
final class SSLJksRestTest extends SSLBaseRestTest {
    /**
     * JKS-file.
     */
    private static final String JKS = "keystore.jks";

    @Override
    Optional<KeyStore> keyStore() throws IOException {
        this.save(
            new Key.From(SSLJksRestTest.JKS),
            new TestResource(String.format("ssl/%s", SSLJksRestTest.JKS)).asBytes()
        );
        return new TestSettings(
            Yaml.createYamlInput(
                String.join(
                    "",
                    "meta:\n",
                    "  ssl:\n",
                    "    enabled: true\n",
                    "    jks:\n",
                    "      path: keystore.jks\n",
                    "      password: secret"
                )
            ).readYamlMapping()
        ).keyStore();
    }
}
