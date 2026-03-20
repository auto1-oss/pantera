/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.security.policy.CachedYamlPolicy;
import com.auto1.pantera.security.policy.Policy;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

/**
 * Test for {@link PanteraSecurity.FromYaml}.
 */
class PanteraSecurityTest {

    private static final Authentication AUTH = (username, password) -> Optional.empty();

    @Test
    void initiatesPolicy() throws IOException {
        final PanteraSecurity security = new PanteraSecurity.FromYaml(
            Yaml.createYamlInput(this.policy()).readYamlMapping(),
            PanteraSecurityTest.AUTH, Optional.empty()
        );
        Assertions.assertInstanceOf(
            PanteraSecurityTest.AUTH.getClass(), security.authentication()
        );
        MatcherAssert.assertThat(
            "Returns provided empty optional",
            security.policyStorage().isEmpty()
        );
        Assertions.assertInstanceOf(CachedYamlPolicy.class, security.policy());
    }

    @Test
    void returnsFreePolicyIfYamlSectionIsAbsent() {
        MatcherAssert.assertThat(
            "Initiates policy",
            new PanteraSecurity.FromYaml(
                Yaml.createYamlMappingBuilder().build(),
                PanteraSecurityTest.AUTH, Optional.empty()
            ).policy(),
            new IsInstanceOf(Policy.FREE.getClass())
        );
    }

    private String policy() {
        return String.join(
            "\n",
            "policy:",
            "  type: artipie",
            "  storage:",
            "    type: fs",
            "    path: /any/path"
        );
    }

}
