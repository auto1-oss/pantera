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
package com.auto1.pantera.security.policy;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.http.auth.AuthUser;
import java.security.Permissions;
import java.util.Collections;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PoliciesLoader}.
 * @since 1.2
 */
public class PoliciesLoaderTest {

    @Test
    void createsYamlPolicy() {
        MatcherAssert.assertThat(
            new PoliciesLoader().newObject(
                "local",
                new YamlPolicyConfig(
                    Yaml.createYamlMappingBuilder().add("type", "local")
                        .add(
                            "storage",
                            Yaml.createYamlMappingBuilder().add("type", "fs")
                                .add("path", "/some/path").build()
                        ).build()
                )
            ),
            new IsInstanceOf(CachedYamlPolicy.class)
        );
    }

    @Test
    void throwsExceptionIfPermNotFound() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new PoliciesLoader().newObject(
                "unknown_policy",
                new YamlPolicyConfig(Yaml.createYamlMappingBuilder().build())
            )
        );
    }

    @Test
    void throwsExceptionIfPermissionsHaveTheSameName() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new PoliciesLoader(
                Collections.singletonMap(
                    PoliciesLoader.SCAN_PACK, "custom.policy.db;custom.policy.duplicate"
                )
            )
        );
    }

    @Test
    void createsExternalPermissions() {
        final PoliciesLoader policy = new PoliciesLoader(
            Collections.singletonMap(
                PoliciesLoader.SCAN_PACK, "custom.policy.db;custom.policy.file"
            )
        );
        MatcherAssert.assertThat(
            "Db policy was created",
            policy.newObject(
                "db-policy",
                new YamlPolicyConfig(Yaml.createYamlMappingBuilder().build())
            ),
            new IsInstanceOf(TestPolicy.class)
        );
        MatcherAssert.assertThat(
            "File policy was created",
            policy.newObject(
                "file-policy",
                new YamlPolicyConfig(Yaml.createYamlMappingBuilder().build())
            ),
            new IsInstanceOf(TestPolicy.class)
        );
    }

    /**
     * Test policy.
     * @since 1.2
     */
    public static final class TestPolicy implements Policy<Permissions> {

        @Override
        public Permissions getPermissions(final AuthUser uname) {
            return new Permissions();
        }
    }
}
