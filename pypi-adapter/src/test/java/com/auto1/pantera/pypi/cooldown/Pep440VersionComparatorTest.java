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
package com.auto1.pantera.pypi.cooldown;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests for {@link Pep440VersionComparator}.
 *
 * <p>Exercises the ordering rules from PEP 440 that differ from semver:
 * pre-releases &lt; release &lt; post-releases, dev-releases sort
 * before the base version, and epochs override everything else.</p>
 *
 * @since 2.2.0
 */
final class Pep440VersionComparatorTest {

    private final Pep440VersionComparator cmp = new Pep440VersionComparator();

    @Test
    void preReleasesSortBeforeRelease() {
        assertThat(this.cmp.compare("2.0.0b1", "2.0.0"), lessThan(0));
        assertThat(this.cmp.compare("2.0.0a1", "2.0.0"), lessThan(0));
        assertThat(this.cmp.compare("2.0.0rc1", "2.0.0"), lessThan(0));
    }

    @Test
    void postReleasesSortAfterRelease() {
        assertThat(this.cmp.compare("2.0.0", "2.0.0.post1"), lessThan(0));
        assertThat(this.cmp.compare("2.0.0.post1", "2.0.0"), greaterThan(0));
        // Multiple post-releases order numerically.
        assertThat(
            this.cmp.compare("2.0.0.post1", "2.0.0.post2"),
            lessThan(0)
        );
    }

    @Test
    void devReleasesSortBeforeBase() {
        // 1.0.0.dev1 < 1.0.0 and < 1.0.0a1
        assertThat(this.cmp.compare("1.0.0.dev1", "1.0.0"), lessThan(0));
        assertThat(this.cmp.compare("1.0.0.dev1", "1.0.0a1"), lessThan(0));
    }

    @Test
    void preReleaseKindOrdering() {
        // Alpha < Beta < RC (per PEP 440).
        assertThat(this.cmp.compare("1.0.0a1", "1.0.0b1"), lessThan(0));
        assertThat(this.cmp.compare("1.0.0b1", "1.0.0rc1"), lessThan(0));
        // Alpha numbers compare numerically.
        assertThat(this.cmp.compare("1.0.0a2", "1.0.0a10"), lessThan(0));
    }

    @Test
    void releaseSegmentsCompareNumerically() {
        assertThat(this.cmp.compare("1.2.3", "1.2.10"), lessThan(0));
        assertThat(this.cmp.compare("1.10.0", "1.2.0"), greaterThan(0));
        // Varying segment lengths — missing segments treated as 0.
        assertThat(this.cmp.compare("1.0", "1.0.0"), equalTo(0));
        assertThat(this.cmp.compare("1", "1.0.1"), lessThan(0));
    }

    @Test
    void epochsDominateReleaseSegment() {
        // 1!1.0.0 > 99.0.0 — explicit epoch bumps ordering.
        assertThat(this.cmp.compare("1!1.0.0", "99.0.0"), greaterThan(0));
        assertThat(this.cmp.compare("2!0.1", "1!9.9.9"), greaterThan(0));
    }

    @Test
    void vPrefixTolerated() {
        assertThat(this.cmp.compare("v1.0.0", "1.0.0"), equalTo(0));
    }

    @Test
    void unparseableSortsBelowParseable() {
        // "not-a-version" is unparseable; sorts before "1.0.0".
        assertThat(this.cmp.compare("not-a-version", "1.0.0"), lessThan(0));
        assertThat(this.cmp.compare("1.0.0", "not-a-version"), greaterThan(0));
    }

    @Test
    void unparseableAmongstSelvesSortLexical() {
        assertThat(
            this.cmp.compare("zz-garbage", "aa-garbage"),
            greaterThan(0)
        );
    }

    @Test
    void totalOrderingOverRoundTrippedSort() {
        // Round-trip sort smoke test — asserts the ordered sequence
        // matches the PEP 440 canonical ordering example (simplified).
        final List<String> versions = new ArrayList<>(List.of(
            "1.0.0.post2",
            "1.0.0.post1",
            "1.0.0",
            "1.0.0rc1",
            "1.0.0b2",
            "1.0.0b1",
            "1.0.0a1",
            "1.0.0.dev2",
            "1.0.0.dev1",
            "0.9.9"
        ));
        Collections.shuffle(versions, new java.util.Random(0xC001D));
        versions.sort(this.cmp);
        assertThat(
            versions,
            equalTo(List.of(
                "0.9.9",
                "1.0.0.dev1",
                "1.0.0.dev2",
                "1.0.0a1",
                "1.0.0b1",
                "1.0.0b2",
                "1.0.0rc1",
                "1.0.0",
                "1.0.0.post1",
                "1.0.0.post2"
            ))
        );
    }

    @Test
    void postReleaseCompactSyntax() {
        // PEP 440 allows '.post', 'post', '.post1', 'post1', '-1' forms.
        assertThat(this.cmp.compare("1.0.0-1", "1.0.0"), greaterThan(0));
        assertThat(this.cmp.compare("1.0.0.post1", "1.0.0-1"), equalTo(0));
    }

    @Test
    void alphaBetaLongFormsEquivalentToShort() {
        assertThat(this.cmp.compare("1.0.0alpha1", "1.0.0a1"), equalTo(0));
        assertThat(this.cmp.compare("1.0.0beta2", "1.0.0b2"), equalTo(0));
    }
}
