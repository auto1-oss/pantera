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
package com.auto1.pantera.http.observability;

import java.util.EnumMap;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Table-driven test for {@link LevelPolicy} — §4.2 of
 * {@code docs/analysis/v2.2-target-architecture.md}.
 *
 * <p>The expected table below is the source of truth. When the spec updates
 * a mapping, change the table; the test will then drive the code change.
 */
final class LevelPolicyTest {

    private static final Map<LevelPolicy, Level> EXPECTED = new EnumMap<>(LevelPolicy.class);

    static {
        // Tier-1
        EXPECTED.put(LevelPolicy.CLIENT_FACING_SUCCESS, Level.DEBUG);
        EXPECTED.put(LevelPolicy.CLIENT_FACING_NOT_FOUND, Level.INFO);
        EXPECTED.put(LevelPolicy.CLIENT_FACING_UNAUTH, Level.INFO);
        EXPECTED.put(LevelPolicy.CLIENT_FACING_4XX_OTHER, Level.WARN);
        EXPECTED.put(LevelPolicy.CLIENT_FACING_5XX, Level.ERROR);
        EXPECTED.put(LevelPolicy.CLIENT_FACING_SLOW, Level.WARN);
        // Tier-2
        EXPECTED.put(LevelPolicy.INTERNAL_CALL_SUCCESS, Level.DEBUG);
        EXPECTED.put(LevelPolicy.INTERNAL_CALL_NOT_FOUND, Level.DEBUG);
        EXPECTED.put(LevelPolicy.INTERNAL_CALL_500, Level.ERROR);
        // Tier-3
        EXPECTED.put(LevelPolicy.UPSTREAM_SUCCESS, Level.DEBUG);
        EXPECTED.put(LevelPolicy.UPSTREAM_NOT_FOUND, Level.DEBUG);
        EXPECTED.put(LevelPolicy.UPSTREAM_5XX, Level.ERROR);
        // Tier-4
        EXPECTED.put(LevelPolicy.LOCAL_CONFIG_CHANGE, Level.INFO);
        EXPECTED.put(LevelPolicy.LOCAL_OP_SUCCESS, Level.DEBUG);
        EXPECTED.put(LevelPolicy.LOCAL_DEGRADED, Level.WARN);
        EXPECTED.put(LevelPolicy.LOCAL_FAILURE, Level.ERROR);
        // Tier-5
        EXPECTED.put(LevelPolicy.AUDIT_EVENT, Level.INFO);
    }

    @Test
    @DisplayName("Every enum member has an expected Level in the spec table")
    void enumIsCompleteInExpectedTable() {
        for (final LevelPolicy p : LevelPolicy.values()) {
            MatcherAssert.assertThat(
                "LevelPolicy." + p.name() + " missing from expected table — "
                    + "add a row to EXPECTED or remove the enum member",
                EXPECTED.containsKey(p), Matchers.is(true)
            );
        }
    }

    @Test
    @DisplayName("Every enum member maps to the Level declared in §4.2")
    void everyMemberMapsToExpectedLevel() {
        for (final LevelPolicy p : LevelPolicy.values()) {
            MatcherAssert.assertThat(
                "Level for " + p.name(),
                p.level(), Matchers.is(EXPECTED.get(p))
            );
        }
    }

    @Test
    @DisplayName("Tier-1 not-found / unauth fall to INFO (WI-00 downgrade from WARN)")
    void tier1ClientNoiseIsInfoNotWarn() {
        MatcherAssert.assertThat(
            LevelPolicy.CLIENT_FACING_NOT_FOUND.level(), Matchers.is(Level.INFO)
        );
        MatcherAssert.assertThat(
            LevelPolicy.CLIENT_FACING_UNAUTH.level(), Matchers.is(Level.INFO)
        );
    }

    @Test
    @DisplayName("Audit is INFO — never suppressed by operational level config")
    void auditIsAtInfo() {
        MatcherAssert.assertThat(
            LevelPolicy.AUDIT_EVENT.level(), Matchers.is(Level.INFO)
        );
    }

    @Test
    @DisplayName("Every 5xx / failure tier maps to ERROR")
    void failureTiersAreError() {
        MatcherAssert.assertThat(LevelPolicy.CLIENT_FACING_5XX.level(), Matchers.is(Level.ERROR));
        MatcherAssert.assertThat(LevelPolicy.INTERNAL_CALL_500.level(), Matchers.is(Level.ERROR));
        MatcherAssert.assertThat(LevelPolicy.UPSTREAM_5XX.level(), Matchers.is(Level.ERROR));
        MatcherAssert.assertThat(LevelPolicy.LOCAL_FAILURE.level(), Matchers.is(Level.ERROR));
    }
}
