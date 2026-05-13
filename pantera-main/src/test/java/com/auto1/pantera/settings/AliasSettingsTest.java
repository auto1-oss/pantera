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
package com.auto1.pantera.settings;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link AliasSettings}.
 * @since 0.28
 */
class AliasSettingsTest {

    /**
     * Test storage.
     */
    private Storage asto;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
    }

    @ParameterizedTest
    @ValueSource(strings =
        {"alice/my-maven/_storages.yaml", "alice/_storages.yaml", "_storages.yaml"}
    )
    void findsRepoAliases(final String aliases) {
        this.asto.save(
            new Key.From(aliases),
            new Content.From(
                String.join(
                    "\n",
                    "storages:",
                    "  def:",
                    "    type: fs",
                    "    path: any"
                ).getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        MatcherAssert.assertThat(
            new AliasSettings(this.asto).find(new Key.From("alice/my-maven")).join()
                .storage(new TestStoragesCache(), "def").identifier(),
            new IsEqual<>("FS: any")
        );
    }

    @ParameterizedTest
    @ValueSource(strings =
        {"alice/my-maven/_storages.yaml", "alice/_storages.yaml"}
    )
    void throwsErrorIfNotFound(final String aliases) {
        this.asto.save(
            new Key.From(aliases),
            new Content.From(
                String.join(
                    "\n",
                    "storages:",
                    "  def:",
                    "    type: fs",
                    "    path: any"
                ).getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> new AliasSettings(this.asto).find(new Key.From("john/my-deb")).join()
                .storage(new TestStoragesCache(), "def")
        );
    }

}
