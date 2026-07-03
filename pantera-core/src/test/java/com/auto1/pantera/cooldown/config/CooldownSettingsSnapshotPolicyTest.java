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
package com.auto1.pantera.cooldown.config;

import java.time.Duration;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link CooldownSettings.SnapshotPolicy} value semantics and the
 * round-trip through {@link CooldownSettings#snapshotPolicy()} /
 * {@link CooldownSettings#setRepoNameSnapshotOverride}.
 *
 * @since 2.2.0
 */
class CooldownSettingsSnapshotPolicyTest {

    @Test
    void inheritPolicyHasBothFieldsAbsent() {
        final CooldownSettings.SnapshotPolicy inherit = CooldownSettings.SnapshotPolicy.inherit();
        MatcherAssert.assertThat(
            "enabled absent", inherit.enabled(), new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "minAge absent", inherit.minimumAllowedAge(), new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "isInherit", inherit.isInherit(), new IsEqual<>(true)
        );
    }

    @Test
    void ofWithBothNullCollapsesToInheritSingleton() {
        MatcherAssert.assertThat(
            CooldownSettings.SnapshotPolicy.of(null, null).isInherit(),
            new IsEqual<>(true)
        );
    }

    @Test
    void ofWithPartialFieldsRetainsSetField() {
        final CooldownSettings.SnapshotPolicy partial =
            CooldownSettings.SnapshotPolicy.of(true, null);
        MatcherAssert.assertThat(
            "enabled set", partial.enabled(), new IsEqual<>(Optional.of(true))
        );
        MatcherAssert.assertThat(
            "minAge absent", partial.minimumAllowedAge(), new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "not inherit", partial.isInherit(), new IsEqual<>(false)
        );
    }

    @Test
    void globalSnapshotPolicyDefaultsToInherit() {
        final CooldownSettings settings = CooldownSettings.defaults();
        MatcherAssert.assertThat(
            settings.snapshotPolicy().isInherit(),
            new IsEqual<>(true)
        );
    }

    @Test
    void repoNameSnapshotOverridesRoundTrip() {
        final CooldownSettings settings = CooldownSettings.defaults();
        settings.setRepoNameSnapshotOverride(
            "internal-mvn",
            CooldownSettings.SnapshotPolicy.of(true, Duration.ofDays(30))
        );
        final CooldownSettings.SnapshotPolicy stored =
            settings.repoNameSnapshotOverrides().get("internal-mvn");
        MatcherAssert.assertThat(
            "enabled persisted", stored.enabled(), new IsEqual<>(Optional.of(true))
        );
        MatcherAssert.assertThat(
            "minAge persisted",
            stored.minimumAllowedAge(),
            new IsEqual<>(Optional.of(Duration.ofDays(30)))
        );
    }

    @Test
    void setRepoNameSnapshotOverrideWithNullRemovesEntry() {
        final CooldownSettings settings = CooldownSettings.defaults();
        settings.setRepoNameSnapshotOverride(
            "internal-mvn", CooldownSettings.SnapshotPolicy.of(true, Duration.ofDays(14))
        );
        settings.setRepoNameSnapshotOverride("internal-mvn", null);
        MatcherAssert.assertThat(
            settings.repoNameSnapshotOverrides().containsKey("internal-mvn"),
            new IsEqual<>(false)
        );
    }

    @Test
    void setSnapshotPolicyAcceptsNullAsInherit() {
        final CooldownSettings settings = CooldownSettings.defaults();
        settings.setSnapshotPolicy(
            CooldownSettings.SnapshotPolicy.of(false, Duration.ofDays(1))
        );
        settings.setSnapshotPolicy(null);
        MatcherAssert.assertThat(
            settings.snapshotPolicy().isInherit(), new IsEqual<>(true)
        );
    }
}
