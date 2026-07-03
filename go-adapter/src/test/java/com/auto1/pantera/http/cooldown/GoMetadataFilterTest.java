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
package com.auto1.pantera.http.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests for {@link GoMetadataFilter}.
 *
 * @since 2.2.0
 */
final class GoMetadataFilterTest {

    private GoMetadataFilter filter;

    @BeforeEach
    void setUp() {
        this.filter = new GoMetadataFilter();
    }

    @Test
    void removesBlockedVersions() {
        final List<String> metadata = new ArrayList<>(
            List.of("v0.1.0", "v0.2.0", "v1.0.0", "v1.1.0", "v2.0.0")
        );
        final List<String> filtered = this.filter.filter(
            metadata, Set.of("v0.2.0", "v1.1.0")
        );
        assertThat(filtered, hasSize(3));
        assertThat(filtered, contains("v0.1.0", "v1.0.0", "v2.0.0"));
    }

    @Test
    void returnsSameInstanceWhenNoBlockedVersions() {
        final List<String> metadata = List.of("v1.0.0", "v2.0.0");
        final List<String> filtered = this.filter.filter(
            metadata, Collections.emptySet()
        );
        assertThat(filtered, is(sameInstance(metadata)));
    }

    @Test
    void removesAllVersionsWhenAllBlocked() {
        final List<String> metadata = new ArrayList<>(
            List.of("v1.0.0", "v2.0.0")
        );
        final List<String> filtered = this.filter.filter(
            metadata, Set.of("v1.0.0", "v2.0.0")
        );
        assertThat(filtered, is(empty()));
    }

    @Test
    void ignoresBlockedVersionsNotInList() {
        final List<String> metadata = new ArrayList<>(
            List.of("v1.0.0", "v2.0.0")
        );
        final List<String> filtered = this.filter.filter(
            metadata, Set.of("v3.0.0", "v4.0.0")
        );
        assertThat(filtered, hasSize(2));
        assertThat(filtered, contains("v1.0.0", "v2.0.0"));
    }

    @Test
    void removesSingleBlockedVersion() {
        final List<String> metadata = new ArrayList<>(
            List.of("v1.0.0", "v2.0.0", "v3.0.0")
        );
        final List<String> filtered = this.filter.filter(
            metadata, Set.of("v2.0.0")
        );
        assertThat(filtered, contains("v1.0.0", "v3.0.0"));
    }

    @Test
    void updateLatestReturnsUnchangedMetadata() {
        final List<String> metadata = List.of("v1.0.0", "v2.0.0");
        final List<String> updated = this.filter.updateLatest(metadata, "v1.0.0");
        assertThat(updated, is(sameInstance(metadata)));
    }

    @Test
    void handlesPreReleaseVersions() {
        final List<String> metadata = new ArrayList<>(
            List.of("v1.0.0", "v2.0.0-beta.1", "v2.0.0-rc.1", "v2.0.0")
        );
        final List<String> filtered = this.filter.filter(
            metadata, Set.of("v2.0.0-beta.1", "v2.0.0-rc.1")
        );
        assertThat(filtered, contains("v1.0.0", "v2.0.0"));
    }

    @Test
    void preservesOrderAfterFiltering() {
        final List<String> metadata = new ArrayList<>(
            List.of("v0.1.0", "v0.2.0", "v1.0.0", "v1.1.0", "v2.0.0")
        );
        final List<String> filtered = this.filter.filter(
            metadata, Set.of("v0.1.0", "v1.0.0", "v2.0.0")
        );
        assertThat(filtered, equalTo(List.of("v0.2.0", "v1.1.0")));
    }
}
