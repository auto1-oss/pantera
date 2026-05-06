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
package com.auto1.pantera.security.perms;

import com.amihaiemil.eoyaml.Yaml;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PermissionConfig.FromYamlSequence}.
 * @since 1.3
 */
class PermissionConfigFromYamlSequenceTest {

    @Test
    void readsKeys() {
        MatcherAssert.assertThat(
            new PermissionConfig.FromYamlSequence(
                Yaml.createYamlSequenceBuilder().add("a").add("b").add("c").build()
            ).keys(),
            Matchers.contains("a", "b", "c")
        );
    }

    @Test
    void readsValueByIndex() {
        MatcherAssert.assertThat(
            new PermissionConfig.FromYamlSequence(
                Yaml.createYamlSequenceBuilder().add("a").add("b").add("c").build()
            ).string("1"),
            new IsEqual<>("b")
        );
    }

    @Test
    void readsSequenceByIndex() {
        MatcherAssert.assertThat(
            new PermissionConfig.FromYamlSequence(
                Yaml.createYamlSequenceBuilder().add("a").add(
                    Yaml.createYamlSequenceBuilder().add("sub1").add("sub2").build()
                ).add("c").build()
            ).sequence("1"),
            Matchers.containsInAnyOrder("sub1", "sub2")
        );
    }

    @Test
    void readsSequenceSubConfigByIndex() {
        MatcherAssert.assertThat(
            new PermissionConfig.FromYamlSequence(
                Yaml.createYamlSequenceBuilder().add("a").add(
                    Yaml.createYamlSequenceBuilder().add("sub1").add("sub2").build()
                ).add("c").build()
            ).config("1"),
            new IsInstanceOf(PermissionConfig.FromYamlSequence.class)
        );
    }

    @Test
    void readsMappingSubConfigByIndex() {
        MatcherAssert.assertThat(
            new PermissionConfig.FromYamlSequence(
                Yaml.createYamlSequenceBuilder().add("a").add(
                    Yaml.createYamlMappingBuilder().add("key1", "val1").add("key2", "val2").build()
                ).add("c").build()
            ).config("1"),
            new IsInstanceOf(PermissionConfig.FromYamlMapping.class)
        );
    }

}
