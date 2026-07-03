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
package com.auto1.pantera.audit;

import java.util.Arrays;
import java.util.Set;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the closed {@link AuditAction} enum. Guards the set of
 * audit-event values against accidental expansion — adding a new variant to
 * the audit stream is a compliance / retention-policy decision, not a routine
 * code change, so we assert the exact membership.
 */
final class AuditActionTest {

    @Test
    @DisplayName("AuditAction has exactly four members per §10.4")
    void hasExactlyFourMembers() {
        MatcherAssert.assertThat(AuditAction.values().length, Matchers.is(4));
    }

    @Test
    @DisplayName("AuditAction members are the four canonical event names")
    void membersAreTheFourCanonicalNames() {
        final Set<AuditAction> actual = Set.of(AuditAction.values());
        final Set<AuditAction> expected = Set.of(
            AuditAction.ARTIFACT_PUBLISH,
            AuditAction.ARTIFACT_DOWNLOAD,
            AuditAction.ARTIFACT_DELETE,
            AuditAction.RESOLUTION
        );
        MatcherAssert.assertThat(actual, Matchers.equalTo(expected));
    }

    @Test
    @DisplayName("Non-audit operational events are NOT present")
    void doesNotContainOperationalEvents() {
        final Set<String> names = Set.of(
            Arrays.stream(AuditAction.values())
                .map(Enum::name)
                .toArray(String[]::new)
        );
        MatcherAssert.assertThat(names, Matchers.not(Matchers.hasItem("CACHE_WRITE")));
        MatcherAssert.assertThat(names, Matchers.not(Matchers.hasItem("CACHE_INVALIDATE")));
        MatcherAssert.assertThat(names, Matchers.not(Matchers.hasItem("POOL_INIT")));
    }

    @Test
    @DisplayName("valueOf round-trips every member (assertion against name drift)")
    void valueOfRoundTripsAllMembers() {
        for (final AuditAction a : AuditAction.values()) {
            MatcherAssert.assertThat(AuditAction.valueOf(a.name()), Matchers.is(a));
        }
    }
}
