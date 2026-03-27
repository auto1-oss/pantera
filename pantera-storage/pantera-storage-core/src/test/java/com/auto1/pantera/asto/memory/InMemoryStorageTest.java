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
package com.auto1.pantera.asto.memory;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link InMemoryStorage}.
 *
 * @since 0.18
 */
class InMemoryStorageTest {

    /**
     * Storage being tested.
     */
    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
    }

    @Test
    @Timeout(1)
    void shouldNotBeBlockedByEndlessContent() throws Exception {
        final Key.From key = new Key.From("data");
        this.storage.save(
            key,
            new Content.From(
                ignored -> {
                }
            )
        );
        Thread.sleep(100);
        MatcherAssert.assertThat(
            this.storage.exists(key).get(1, TimeUnit.SECONDS),
            new IsEqual<>(false)
        );
    }
}
