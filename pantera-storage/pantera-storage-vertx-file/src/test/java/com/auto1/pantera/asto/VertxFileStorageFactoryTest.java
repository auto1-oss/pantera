/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
