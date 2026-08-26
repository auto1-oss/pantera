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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.Test;

/**
 * Test cases for {@link PackumentRevision}.
 * @since 2.2.5
 */
final class PackumentRevisionTest {

    @Test
    void isStableAcrossRepeatedReads() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "1.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        final PackumentRevision rev = new PackumentRevision(storage, "pkg");
        MatcherAssert.assertThat(
            rev.value().join(), new IsEqual<>(rev.value().join())
        );
    }

    @Test
    void changesWhenAVersionIsAdded() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "1.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        final String before = new PackumentRevision(storage, "pkg").value().join();
        storage.save(new Key.From("pkg", ".versions", "2.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        final String after = new PackumentRevision(storage, "pkg").value().join();
        MatcherAssert.assertThat(after, new IsNot<>(new IsEqual<>(before)));
    }

    @Test
    void changesWhenADistTagMoves() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "1.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        storage.save(new Key.From("pkg", ".dist-tags.json"),
            new Content.From("{\"latest\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8))).join();
        final String before = new PackumentRevision(storage, "pkg").value().join();
        storage.save(new Key.From("pkg", ".dist-tags.json"),
            new Content.From("{\"latest\":\"1.0.0\",\"next\":\"1.0.0\"}"
                .getBytes(StandardCharsets.UTF_8))).join();
        final String after = new PackumentRevision(storage, "pkg").value().join();
        MatcherAssert.assertThat(after, new IsNot<>(new IsEqual<>(before)));
    }

    @Test
    void countsVersionsInThePrefix() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "1.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        storage.save(new Key.From("pkg", ".versions", "2.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        MatcherAssert.assertThat(
            new PackumentRevision(storage, "pkg").value().join(),
            new StringStartsWith("2-")
        );
    }
}
