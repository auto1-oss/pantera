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

import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Utf8Order} and for the storages that have to sort by it
 * themselves.
 *
 * @since 2.2.14
 */
final class Utf8OrderTest {

    /**
     * Strings whose UTF-16 order differs from their UTF-8 order: a
     * character outside the BMP (a surrogate pair) against U+FF5E.
     */
    private static final List<String> SAMPLES = List.of(
        "k/", "k/a", "k/a-b", "k/a.b", "k/a/b", "k/ab",
        "k/é", "k/я", "k/日", "k/～", "k/😀", "k/😀/x"
    );

    @Test
    void ordersLikeUtf8Bytes() {
        final List<String> byBytes = new ArrayList<>(Utf8OrderTest.SAMPLES);
        byBytes.sort(
            (left, right) -> Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)
            )
        );
        final List<String> sorted = new ArrayList<>(Utf8OrderTest.SAMPLES);
        java.util.Collections.reverse(sorted);
        sorted.sort(new Utf8Order());
        MatcherAssert.assertThat(sorted, new IsEqual<>(byBytes));
    }

    @Test
    void differsFromUtf16OrderForSupplementaryCharacters() {
        MatcherAssert.assertThat(
            "UTF-16 puts the surrogate pair first",
            "😀".compareTo("～") < 0, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "UTF-8 puts it last",
            new Utf8Order().compare("😀", "～") > 0, new IsEqual<>(true)
        );
    }

    @Test
    void inMemoryStorageListsInUtf8Order() {
        MatcherAssert.assertThat(
            Utf8OrderTest.listed(new InMemoryStorage()),
            new IsEqual<>(List.of("k/～", "k/😀", "k/😀😀"))
        );
    }

    @Test
    void fileStorageListsInUtf8Order(@TempDir final Path temp) {
        MatcherAssert.assertThat(
            Utf8OrderTest.listed(new FileStorage(temp)),
            new IsEqual<>(List.of("k/～", "k/😀", "k/😀😀"))
        );
    }

    /**
     * Save three keys and list them page by page.
     * @param storage Storage
     * @return Key strings as listed
     */
    private static List<String> listed(final Storage storage) {
        for (final String key : List.of(
            "k/😀", "k/～", "k/😀😀"
        )) {
            storage.save(new Key.From(key), new Content.From(new byte[]{1})).join();
        }
        final List<String> keys = new ArrayList<>(3);
        Optional<String> token = Optional.empty();
        do {
            final ListPage page = storage.listPage(new Key.From("k"), token, 1).join();
            keys.addAll(page.keys().stream().map(Key::string).collect(Collectors.toList()));
            token = page.next();
        } while (token.isPresent() && keys.size() < 10);
        return keys;
    }
}
