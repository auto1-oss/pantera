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
package com.auto1.pantera.debian;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.slice.KeyFromPath;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link GpgConfig.FromYaml}.
 * @since 0.4
 */
class GpgConfigTest {

    @Test
    void returnsPassword() {
        final String pswd = "123";
        MatcherAssert.assertThat(
            new GpgConfig.FromYaml(
                Yaml.createYamlMappingBuilder()
                    .add(GpgConfig.FromYaml.GPG_PASSWORD, pswd).build(),
                new InMemoryStorage()
            ).password(),
            new IsEqual<>(pswd)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"/one/two/my_key.gpg", "one/some_key.gpg", "key.gpg", "/secret.gpg"})
    void returnsKey(final String key) {
        final byte[] bytes = "abc".getBytes();
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new KeyFromPath(key), new Content.From(bytes)).join();
        MatcherAssert.assertThat(
            new GpgConfig.FromYaml(
                Yaml.createYamlMappingBuilder()
                    .add(GpgConfig.FromYaml.GPG_SECRET_KEY, key).build(),
                storage
            ).key().toCompletableFuture().join(),
            new IsEqual<>(bytes)
        );
    }
}
