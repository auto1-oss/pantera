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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cooldown.config.CooldownSettings;
import java.time.Duration;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link RepoConfigCooldownOverrides}.
 * @since 2.2.9
 */
final class RepoConfigCooldownOverridesTest {

    @Test
    void repositoryCreatedAtRuntimeGetsItsWindow() {
        // B34: the window was registered only at boot.
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(1));
        final RepoConfigCooldownOverrides overrides = new RepoConfigCooldownOverrides(settings);
        final boolean changed = overrides.sync("npm_proxy", Optional.of(Duration.ofDays(3650)));
        MatcherAssert.assertThat("the sync reports a change", changed, new IsEqual<>(true));
        MatcherAssert.assertThat(
            "the repository's own window applies",
            settings.effectiveMinimumAllowedAge("npm-proxy", "npm_proxy"),
            new IsEqual<>(Duration.ofDays(3650))
        );
        MatcherAssert.assertThat(
            "re-syncing the same window is not a change",
            overrides.sync("npm_proxy", Optional.of(Duration.ofDays(3650))),
            new IsEqual<>(false)
        );
    }

    @Test
    void removedWindowFallsBackToTheGlobalOne() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(1));
        final RepoConfigCooldownOverrides overrides = new RepoConfigCooldownOverrides(settings);
        overrides.sync("npm_proxy", Optional.of(Duration.ofDays(30)));
        overrides.sync("npm_proxy", Optional.empty());
        MatcherAssert.assertThat(
            settings.effectiveMinimumAllowedAge("npm-proxy", "npm_proxy"),
            new IsEqual<>(Duration.ofDays(1))
        );
    }

    @Test
    void leavesOverridesItDidNotSetAlone() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(1));
        settings.setRepoNameOverride("admin_set", true, Duration.ofDays(7));
        final boolean changed = new RepoConfigCooldownOverrides(settings)
            .sync("admin_set", Optional.empty());
        MatcherAssert.assertThat("nothing to remove", changed, new IsEqual<>(false));
        MatcherAssert.assertThat(
            "the override set through the cooldown settings API survives",
            settings.effectiveMinimumAllowedAge("npm-proxy", "admin_set"),
            new IsEqual<>(Duration.ofDays(7))
        );
    }
}
