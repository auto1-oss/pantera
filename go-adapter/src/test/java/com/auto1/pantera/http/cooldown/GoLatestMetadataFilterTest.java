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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests for {@link GoLatestMetadataFilter}.
 *
 * <p>The filter is a pure function over a parsed {@link GoLatestInfo}:
 * pass-through when the version is not blocked, {@code null} when it is
 * (to signal that orchestration must resolve a fallback). No upstream
 * I/O happens here; the multi-endpoint flow lives in
 * {@link GoLatestHandler}.</p>
 *
 * @since 2.2.0
 */
final class GoLatestMetadataFilterTest {

    private GoLatestMetadataFilter filter;

    @BeforeEach
    void setUp() {
        this.filter = new GoLatestMetadataFilter();
    }

    @Test
    void passesThroughWhenVersionNotBlocked() {
        final GoLatestInfo info = new GoLatestInfo(
            "v1.2.3", "2024-05-12T00:00:00Z", null
        );
        final GoLatestInfo out = this.filter.filter(info, Set.of("v1.0.0"));
        assertThat(out, is(sameInstance(info)));
    }

    @Test
    void passesThroughWhenNoBlockedVersions() {
        final GoLatestInfo info = new GoLatestInfo("v1.2.3", null, null);
        final GoLatestInfo out = this.filter.filter(info, Collections.emptySet());
        assertThat(out, is(sameInstance(info)));
    }

    @Test
    void returnsNullWhenVersionBlocked() {
        final GoLatestInfo info = new GoLatestInfo(
            "v1.2.3", "2024-05-12T00:00:00Z", null
        );
        final GoLatestInfo out = this.filter.filter(info, Set.of("v1.2.3"));
        assertThat(out, is(nullValue()));
    }

    @Test
    void returnsNullForNullInput() {
        final GoLatestInfo out = this.filter.filter(null, Set.of("v1.2.3"));
        assertThat(out, is(nullValue()));
    }

    @Test
    void updateLatestSwapsVersionAndClearsTime() {
        final GoLatestInfo info = new GoLatestInfo(
            "v1.2.3", "2024-05-12T00:00:00Z", null
        );
        final GoLatestInfo out = this.filter.updateLatest(info, "v1.2.2");
        assertThat(out, is(notNullValue()));
        assertThat(out.version(), equalTo("v1.2.2"));
        // Time cleared — a different version has a different release
        // timestamp; the Go client tolerates a missing Time field.
        assertThat(out.time(), is(nullValue()));
    }

    @Test
    void updateLatestPreservesOriginWhenPresent() {
        final ObjectNode origin = new ObjectMapper().createObjectNode();
        origin.put("VCS", "git");
        origin.put("URL", "https://github.com/foo/bar");
        final GoLatestInfo info = new GoLatestInfo(
            "v1.2.3", "2024-05-12T00:00:00Z", origin
        );
        final GoLatestInfo out = this.filter.updateLatest(info, "v1.2.2");
        assertThat(out.origin(), is(sameInstance(origin)));
        assertThat(out.origin().get("VCS").asText(), equalTo("git"));
    }

    @Test
    void updateLatestReturnsSameWhenVersionMatches() {
        final GoLatestInfo info = new GoLatestInfo("v1.2.3", null, null);
        final GoLatestInfo out = this.filter.updateLatest(info, "v1.2.3");
        assertThat(out, is(sameInstance(info)));
    }

    @Test
    void updateLatestNoOpsOnNullOrEmptyVersion() {
        final GoLatestInfo info = new GoLatestInfo("v1.2.3", null, null);
        assertThat(this.filter.updateLatest(info, null), is(sameInstance(info)));
        assertThat(this.filter.updateLatest(info, ""), is(sameInstance(info)));
    }
}
