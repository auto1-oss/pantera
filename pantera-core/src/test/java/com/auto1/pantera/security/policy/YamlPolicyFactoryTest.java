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
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link YamlPolicyFactory}.
 * @since 1.2
 */
class YamlPolicyFactoryTest {

    @Test
    void createsYamlPolicy() {
        MatcherAssert.assertThat(
            new YamlPolicyFactory().getPolicy(
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
    void createsYamlPolicyWithEviction() {
        MatcherAssert.assertThat(
            new YamlPolicyFactory().getPolicy(
                new YamlPolicyConfig(
                    Yaml.createYamlMappingBuilder().add("type", "local")
                        .add("eviction_millis", "50000")
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

}
