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
package com.auto1.pantera.http.auth;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.PanteraException;
import java.util.Collections;

import custom.auth.first.FirstAuthFactory;
import custom.auth.second.SecondAuthFactory;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link AuthLoader}.
 * @since 1.3
 */
class AuthLoaderTest {

    @Test
    void loadsFactories() {
        final AuthLoader loader = new AuthLoader(
            Collections.singletonMap(
                AuthLoader.SCAN_PACK, "custom.auth.first;custom.auth.second"
            )
        );
        MatcherAssert.assertThat(
            "first auth was created",
            loader.newObject(
                "first",
                Yaml.createYamlMappingBuilder().build()
            ),
            new IsInstanceOf(FirstAuthFactory.FirstAuth.class)
        );
        MatcherAssert.assertThat(
            "second auth was created",
            loader.newObject(
                "second",
                Yaml.createYamlMappingBuilder().build()
            ),
            new IsInstanceOf(SecondAuthFactory.SecondAuth.class)
        );
    }

    @Test
    void throwsExceptionIfPermNotFound() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new AuthLoader().newObject(
                "unknown_policy",
                Yaml.createYamlMappingBuilder().build()
            )
        );
    }

    @Test
    void throwsExceptionIfPermissionsHaveTheSameName() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new AuthLoader(
                Collections.singletonMap(
                    AuthLoader.SCAN_PACK, "custom.auth.first;custom.auth.duplicate"
                )
            )
        );
    }

}
