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
package com.auto1.pantera.prefetch.parser;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.prefetch.parser.CachedNpmMetadataLookup.NamedStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CachedNpmMetadataLookup}. Each test seeds an
 * {@link InMemoryStorage} with a hand-rolled packument and asserts the
 * resolver picks the highest version satisfying the requested range.
 *
 * @since 2.2.0
 */
class CachedNpmMetadataLookupTest {

    @Test
    void exactVersionResolves() {
        final Storage store = seed(
            "left-pad",
            "{\"versions\":{\"1.2.0\":{},\"1.3.0\":{},\"1.4.0\":{}}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            lookup.resolve("left-pad", "1.3.0"),
            Matchers.is(Optional.of("1.3.0"))
        );
    }

    @Test
    void caretRangeResolvesToHighestSatisfying() {
        final Storage store = seed(
            "lodash",
            "{\"versions\":{\"4.16.0\":{},\"4.17.0\":{},\"4.17.21\":{},\"5.0.0\":{}}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            "^4.17.0 must resolve to highest 4.x.x, not 5.0.0",
            lookup.resolve("lodash", "^4.17.0"),
            Matchers.is(Optional.of("4.17.21"))
        );
    }

    @Test
    void tildeRangeResolvesToHighestSatisfying() {
        final Storage store = seed(
            "express",
            "{\"versions\":{"
                + "\"4.17.0\":{},\"4.17.1\":{},\"4.17.21\":{},"
                + "\"4.18.0\":{},\"5.0.0\":{}"
                + "}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            "~4.17.0 must resolve to highest 4.17.x",
            lookup.resolve("express", "~4.17.0"),
            Matchers.is(Optional.of("4.17.21"))
        );
    }

    @Test
    void rangeWithMultipleAlternatives() {
        final Storage store = seed(
            "react",
            "{\"versions\":{\"16.8.0\":{},\"17.0.2\":{},\"18.0.0\":{},\"18.2.0\":{}}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            "16.x || 17.x must pick the highest of either alternative",
            lookup.resolve("react", "16.x || 17.x"),
            Matchers.is(Optional.of("17.0.2"))
        );
    }

    @Test
    void prereleaseExcludedByDefault() {
        final Storage store = seed(
            "next",
            "{\"versions\":{"
                + "\"13.0.0\":{},\"13.1.0\":{},\"14.0.0-rc.1\":{}"
                + "}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            "^13.0.0 must NOT pick a 14.0.0 prerelease",
            lookup.resolve("next", "^13.0.0"),
            Matchers.is(Optional.of("13.1.0"))
        );
    }

    @Test
    void prereleaseAllowedWhenRangeAsksForIt() {
        final Storage store = seed(
            "next",
            "{\"versions\":{"
                + "\"14.0.0-rc.1\":{},\"14.0.0-rc.2\":{},\"14.0.0-rc.5\":{}"
                + "}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            "Range with explicit prerelease tag must allow prerelease versions",
            lookup.resolve("next", "^14.0.0-rc.1"),
            Matchers.is(Optional.of("14.0.0-rc.5"))
        );
    }

    @Test
    void missingMetadataReturnsEmpty() {
        final CachedNpmMetadataLookup lookup = lookupOf(new InMemoryStorage());
        MatcherAssert.assertThat(
            lookup.resolve("does-not-exist", "^1.0.0"),
            Matchers.is(Optional.empty())
        );
    }

    @Test
    void abbreviatedMetadataIsPreferred() {
        // Both files exist; abbreviated has only 1.0.0, full has 1.0.0 + 2.0.0.
        // Lookup must pick from abbreviated -> 1.0.0 wins for ^1.0.0.
        final Storage store = new InMemoryStorage();
        save(
            store, "pkg/meta.abbreviated.json",
            "{\"versions\":{\"1.0.0\":{}}}"
        );
        save(
            store, "pkg/meta.json",
            "{\"versions\":{\"1.0.0\":{},\"2.0.0\":{}}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            "Abbreviated packument must be consulted first",
            lookup.resolve("pkg", "^1.0.0"),
            Matchers.is(Optional.of("1.0.0"))
        );
    }

    @Test
    void fallsBackToFullPackumentWhenAbbreviatedAbsent() {
        final Storage store = new InMemoryStorage();
        // ONLY meta.json exists — older cache entries written before the
        // abbreviated path was added should still resolve.
        save(
            store, "old-pkg/meta.json",
            "{\"versions\":{\"3.1.4\":{}}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            lookup.resolve("old-pkg", "^3.0.0"),
            Matchers.is(Optional.of("3.1.4"))
        );
    }

    @Test
    void scopedPackageNameSplitCorrectly() {
        final Storage store = new InMemoryStorage();
        save(
            store, "@types/node/meta.abbreviated.json",
            "{\"versions\":{\"20.10.0\":{},\"20.11.0\":{},\"21.0.0\":{}}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            "Scoped names must be looked up under '<scope>/<name>/meta.*'",
            lookup.resolve("@types/node", "^20.10.0"),
            Matchers.is(Optional.of("20.11.0"))
        );
    }

    @Test
    void unparseableRangeReturnsEmpty() {
        final Storage store = seed(
            "weird",
            "{\"versions\":{\"1.0.0\":{}}}"
        );
        final CachedNpmMetadataLookup lookup = lookupOf(store);
        MatcherAssert.assertThat(
            "git+ssh ranges, file: ranges, etc. must be silently skipped",
            lookup.resolve("weird", "git+ssh://git@github.com/foo/bar"),
            Matchers.is(Optional.empty())
        );
    }

    @Test
    void emptyStoragesListReturnsEmpty() {
        final CachedNpmMetadataLookup lookup =
            new CachedNpmMetadataLookup(java.util.Collections::emptyList);
        MatcherAssert.assertThat(
            lookup.resolve("anything", "^1.0.0"),
            Matchers.is(Optional.empty())
        );
    }

    @Test
    void firstMatchingProxyWins() {
        final Storage primary = seed(
            "shared",
            "{\"versions\":{\"1.5.0\":{}}}"
        );
        final Storage secondary = seed(
            "shared",
            "{\"versions\":{\"1.5.0\":{},\"2.0.0\":{}}}"
        );
        final CachedNpmMetadataLookup lookup = new CachedNpmMetadataLookup(
            () -> List.of(
                new NamedStorage("primary-npm", primary),
                new NamedStorage("secondary-npm", secondary)
            )
        );
        MatcherAssert.assertThat(
            "Lookup must consult storages in priority order, not merge them.",
            lookup.resolve("shared", "*"),
            Matchers.is(Optional.of("1.5.0"))
        );
    }

    private static CachedNpmMetadataLookup lookupOf(final Storage store) {
        return new CachedNpmMetadataLookup(
            () -> List.of(new NamedStorage("npm-proxy", store))
        );
    }

    private static Storage seed(final String packageName, final String json) {
        final Storage store = new InMemoryStorage();
        save(store, packageName + "/meta.abbreviated.json", json);
        return store;
    }

    private static void save(final Storage store, final String key, final String json) {
        store.save(
            new Key.From(key),
            new Content.From(json.getBytes(StandardCharsets.UTF_8))
        ).join();
    }
}
