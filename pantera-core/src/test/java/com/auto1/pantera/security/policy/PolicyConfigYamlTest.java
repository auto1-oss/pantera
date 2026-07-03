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
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link YamlPolicyConfig}.
 * @since 1.2
 */
class PolicyConfigYamlTest {

    @Test
    void readsStringValue() {
        MatcherAssert.assertThat(
            new YamlPolicyConfig(Yaml.createYamlMappingBuilder().add("key", "value").build())
                .string("key"),
            new IsEqual<>("value")
        );
    }

    @Test
    void readsSequence() {
        MatcherAssert.assertThat(
            new YamlPolicyConfig(
                Yaml.createYamlMappingBuilder()
                    .add("key", Yaml.createYamlSequenceBuilder().add("one").add("two").build())
                    .build()
            ).sequence("key"),
            Matchers.contains("one", "two")
        );
    }

    @Test
    void returnsEmptySequenceWhenAbsent() {
        MatcherAssert.assertThat(
            new YamlPolicyConfig(Yaml.createYamlMappingBuilder().build()).sequence("key"),
            new IsEmptyCollection<>()
        );
    }

    @Test
    void readsSubConfig() {
        MatcherAssert.assertThat(
            new YamlPolicyConfig(
                Yaml.createYamlMappingBuilder().add(
                    "key", Yaml.createYamlMappingBuilder().add("sub_key", "sub_value").build()
                ).build()
            ).config("key"),
            new IsInstanceOf(YamlPolicyConfig.class)
        );
    }

}
