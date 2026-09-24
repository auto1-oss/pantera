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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link GoListPruner}.
 *
 * @since 2.2.9
 */
final class GoListPrunerTest {

    /**
     * Version list key.
     */
    private static final Key LIST = new Key.From("example.com/mod/@v/list");

    /**
     * Storage.
     */
    private Storage asto;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        for (final String ver : new String[] {"v1.0.0", "v1.1.0"}) {
            for (final String ext : new String[] {".zip", ".mod", ".info"}) {
                this.asto.save(
                    new Key.From("example.com/mod/@v/" + ver + ext), Content.EMPTY
                ).join();
            }
        }
        this.asto.save(
            GoListPrunerTest.LIST,
            new Content.From("v1.0.0\nv1.1.0\n".getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    @Test
    void dropsTheDeletedVersionFromTheList() {
        this.asto.delete(new Key.From("example.com/mod/@v/v1.1.0.zip")).join();
        new GoListPruner(this.asto, "go-local")
            .afterDelete("example.com/mod/@v/v1.1.0.zip").join();
        MatcherAssert.assertThat(
            this.asto.value(GoListPrunerTest.LIST).join().asString(),
            new IsEqual<>("v1.0.0\n")
        );
    }

    @Test
    void removesTheListWhenNoVersionIsLeft() {
        this.asto.delete(new Key.From("example.com/mod/@v/v1.0.0.zip")).join();
        this.asto.delete(new Key.From("example.com/mod/@v/v1.1.0.zip")).join();
        new GoListPruner(this.asto, "go-local").afterDelete("/example.com/mod/@v/").join();
        MatcherAssert.assertThat(
            this.asto.exists(GoListPrunerTest.LIST).join(), new IsEqual<>(false)
        );
    }

    @Test
    void leavesTheListAloneOutsideAVersionDirectory() {
        this.asto.delete(new Key.From("example.com/mod/@v/v1.1.0.zip")).join();
        new GoListPruner(this.asto, "go-local").afterDelete("example.com/other").join();
        MatcherAssert.assertThat(
            this.asto.value(GoListPrunerTest.LIST).join().asString(),
            new IsEqual<>("v1.0.0\nv1.1.0\n")
        );
    }
}
