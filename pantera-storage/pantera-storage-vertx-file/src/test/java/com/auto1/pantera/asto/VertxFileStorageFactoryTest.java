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
package com.auto1.pantera.asto;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import com.auto1.pantera.asto.fs.VertxFileStorage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test for Storages.
 */
public final class VertxFileStorageFactoryTest {

    @Test
    void shouldCreateVertxFileStorage() {
        MatcherAssert.assertThat(
            StoragesLoader.STORAGES
                .newObject(
                    "vertx-file",
                    new Config.YamlStorageConfig(
                        Yaml.createYamlMappingBuilder().add("path", "").build()
                    )
                ),
            new IsInstanceOf(VertxFileStorage.class)
        );
    }
}
