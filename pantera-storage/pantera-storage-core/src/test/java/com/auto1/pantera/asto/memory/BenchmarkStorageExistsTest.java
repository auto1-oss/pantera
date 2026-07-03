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
import java.util.NavigableMap;
import java.util.TreeMap;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BenchmarkStorage#exists(Key)}.
 * @since 1.2.0
 */
final class BenchmarkStorageExistsTest {
    @Test
    void existsWhenPresentInLocalAndNotDeleted() {
        final InMemoryStorage memory = new InMemoryStorage();
        final BenchmarkStorage bench = new BenchmarkStorage(memory);
        final Key key = new Key.From("somekey");
        bench.save(key, Content.EMPTY).join();
        MatcherAssert.assertThat(
            bench.exists(key).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void existsWhenPresentInBackendAndNotDeleted() {
        final Key key = new Key.From("somekey");
        final NavigableMap<String, byte[]> backdata = new TreeMap<>();
        backdata.put(key.string(), "shouldExist".getBytes());
        final InMemoryStorage memory = new InMemoryStorage(backdata);
        final BenchmarkStorage bench = new BenchmarkStorage(memory);
        MatcherAssert.assertThat(
            bench.exists(key).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void notExistsIfKeyWasDeleted() {
        final InMemoryStorage memory = new InMemoryStorage();
        final BenchmarkStorage bench = new BenchmarkStorage(memory);
        final Key key = new Key.From("somekey");
        bench.save(key, new Content.From("any data".getBytes())).join();
        bench.delete(key).join();
        MatcherAssert.assertThat(
            bench.exists(key).join(),
            new IsEqual<>(false)
        );
    }
}
