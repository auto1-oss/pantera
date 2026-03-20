/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.auth;

import com.amihaiemil.eoyaml.Yaml;
import java.io.IOException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link AuthFromStorageFactory}.
 * @since 0.30
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AuthFromStorageFactoryTest {

    @Test
    void initsWhenStorageForAuthIsSet() throws IOException {
        MatcherAssert.assertThat(
            new AuthFromStorageFactory().getAuthentication(
                Yaml.createYamlInput(this.panteraEnvCreds()).readYamlMapping()
            ),
            new IsInstanceOf(AuthFromStorage.class)
        );
    }

    @Test
    void initsWhenPolicyIsSet() throws IOException {
        MatcherAssert.assertThat(
            new AuthFromStorageFactory().getAuthentication(
                Yaml.createYamlInput(this.panteraGithubCredsAndPolicy()).readYamlMapping()
            ),
            new IsInstanceOf(AuthFromStorage.class)
        );
    }

    private String panteraEnvCreds() {
        return String.join(
            "\n",
            "credentials:",
            "  - type: env",
            "  - type: local",
            "    storage:",
            "      type: fs",
            "      path: any"
        );
    }

    private String panteraGithubCredsAndPolicy() {
        return String.join(
            "\n",
            "credentials:",
            "  - type: github",
            "  - type: local",
            "policy:",
            "  type: local",
            "  storage:",
            "    type: fs",
            "    path: /any/path"
        );
    }

}
