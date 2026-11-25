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
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RekeyedStorage}: a renamed repository's files, stored
 * under its key, are addressed by its name.
 *
 * @since 2.2.14
 */
final class RekeyedStorageTest {

    /**
     * Raw storage: the renamed repository's directory is {@code libs}.
     */
    private Storage raw;

    /**
     * View addressed by the new name {@code releases}.
     */
    private Storage view;

    @BeforeEach
    void init() {
        this.raw = new InMemoryStorage();
        RekeyedStorageTest.put(this.raw, "libs/a/one.jar");
        RekeyedStorageTest.put(this.raw, "libs/a/two.jar");
        RekeyedStorageTest.put(this.raw, "libs-proxy/other.jar");
        this.view = new RekeyedStorage(this.raw, "releases", "libs");
    }

    @Test
    void readsByNameWhatIsStoredUnderTheKey() {
        MatcherAssert.assertThat(
            "a file is found under the name",
            this.view.exists(new Key.From("releases/a/one.jar")).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "its content is read",
            new String(
                this.view.value(new Key.From("releases/a/one.jar")).join().asBytes(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>("libs/a/one.jar")
        );
    }

    @Test
    void listsKeysUnderTheName() {
        MatcherAssert.assertThat(
            this.view.list(new Key.From("releases")).join().stream().map(Key::string).sorted().toList(),
            new IsEqual<>(List.of("releases/a/one.jar", "releases/a/two.jar"))
        );
    }

    @Test
    void pagesKeysUnderTheNameAndResumesWithItsOwnToken() {
        final ListPage first = this.view.listPage(new Key.From("releases"), Optional.empty(), 1).join();
        final ListPage second = this.view.listPage(new Key.From("releases"), first.next(), 5).join();
        MatcherAssert.assertThat(
            "the first page is addressed by name",
            first.keys().stream().map(Key::string).toList(),
            new IsEqual<>(List.of("releases/a/one.jar"))
        );
        MatcherAssert.assertThat(
            "the token of the first page resumes the listing",
            second.keys().stream().map(Key::string).toList(),
            new IsEqual<>(List.of("releases/a/two.jar"))
        );
    }

    @Test
    void writesAndDeletesGoToTheKeyDirectory() {
        this.view.save(
            new Key.From("releases/b/new.jar"), new Content.From("n".getBytes(StandardCharsets.UTF_8))
        ).join();
        this.view.delete(new Key.From("releases/a/one.jar")).join();
        this.view.deleteBatch(List.of(new Key.From("releases/a/two.jar"))).join();
        MatcherAssert.assertThat(
            this.raw.list(Key.ROOT).join().stream().map(Key::string).sorted().toList(),
            new IsEqual<>(List.of("libs-proxy/other.jar", "libs/b/new.jar"))
        );
    }

    @Test
    void doesNotTouchADirectoryThatOnlyStartsWithTheName() {
        RekeyedStorageTest.put(this.raw, "releases-old/keep.jar");
        MatcherAssert.assertThat(
            "a sibling whose name starts the same is addressed as it is",
            this.view.exists(new Key.From("releases-old/keep.jar")).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and is not listed under the name",
            this.view.list(new Key.From("releases")).join().size(), new IsEqual<>(2)
        );
    }

    /**
     * Save an object whose content is its key.
     * @param storage Storage
     * @param key Key
     */
    private static void put(final Storage storage, final String key) {
        storage.save(new Key.From(key), new Content.From(key.getBytes(StandardCharsets.UTF_8))).join();
    }
}
