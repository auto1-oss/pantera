/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.factory;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.fs.FileStorage;
import com.third.party.factory.first2.TestFirst2StorageFactory;
import java.util.Collections;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for Storages.
 *
 * @since 1.13.0
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class StoragesLoaderTest {

    @Test
    void shouldCreateFileStorage() {
        MatcherAssert.assertThat(
            StoragesLoader.STORAGES
                .newObject(
                    "fs",
                    new Config.YamlStorageConfig(Yaml.createYamlMappingBuilder()
                        .add("path", "")
                        .build()
                    )
                ),
            new IsInstanceOf(FileStorage.class)
        );
    }

    @Test
    void shouldThrowExceptionWhenTypeIsWrong() {
        Assertions.assertThrows(
            StorageNotFoundException.class,
            () -> StoragesLoader.STORAGES
                .newObject(
                    "wrong-storage-type",
                    new Config.YamlStorageConfig(Yaml.createYamlMappingBuilder().build())
                )
        );
    }

    @Test
    void shouldThrowExceptionWhenReadTwoFactoryWithTheSameName() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new StoragesLoader(
                Collections.singletonMap(
                    StoragesLoader.SCAN_PACK,
                    "com.third.party.factory.first;com.third.party.factory.first2"
                )
            ),
            String.format(
                "Storage factory with type 'test-first' already exists [class=%s].",
                TestFirst2StorageFactory.class.getSimpleName()
            )
        );
    }

    @Test
    void shouldScanAdditionalPackageFromEnv() {
        MatcherAssert.assertThat(
            new StoragesLoader(
                Collections.singletonMap(
                    StoragesLoader.SCAN_PACK,
                    "com.third.party.factory.first"
                )
            ).types(),
            Matchers.containsInAnyOrder("fs", "test-first")
        );
    }

    @Test
    void shouldScanSeveralPackagesFromEnv() {
        MatcherAssert.assertThat(
            new StoragesLoader(
                Collections.singletonMap(
                    StoragesLoader.SCAN_PACK,
                    "com.third.party.factory.first;com.third.party.factory.second"
                )
            ).types(),
            Matchers.containsInAnyOrder("fs", "test-first", "test-second")
        );
    }
}
