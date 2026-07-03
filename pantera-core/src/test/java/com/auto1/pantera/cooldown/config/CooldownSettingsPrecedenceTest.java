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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

/**
 * Tests for {@link CooldownSettings#effectiveEnabled(String, String)} and
 * {@link CooldownSettings#effectiveMinimumAllowedAge(String, String)} — the
 * canonical per-name → per-type → global precedence chain shared between
 * {@code JdbcCooldownService} and {@code MetadataFilterService}.
 */
final class CooldownSettingsPrecedenceTest {

    @Test
    void perNameOverrideBeatsPerTypeWhenBothSetForEnabled() {
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofHours(72),
            Map.of("maven-proxy", new CooldownSettings.RepoTypeConfig(true, Duration.ofDays(60)))
        );
        settings.setRepoNameOverride("gradle_proxy", false, Duration.ofDays(7));
        MatcherAssert.assertThat(
            "per-name disabled override beats per-type enabled override",
            settings.effectiveEnabled("maven-proxy", "gradle_proxy"),
            new IsEqual<>(false)
        );
    }

    @Test
    void perNameOverrideBeatsPerTypeWhenBothSetForDuration() {
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofHours(72),
            Map.of("maven-proxy", new CooldownSettings.RepoTypeConfig(true, Duration.ofDays(60)))
        );
        settings.setRepoNameOverride("gradle_proxy", true, Duration.ofDays(7));
        MatcherAssert.assertThat(
            "per-name 7-day duration beats per-type 60-day duration",
            settings.effectiveMinimumAllowedAge("maven-proxy", "gradle_proxy"),
            new IsEqual<>(Duration.ofDays(7))
        );
    }

    @Test
    void perTypeOverrideBeatsGlobalWhenPerNameAbsentForEnabled() {
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofHours(72),
            Map.of("npm-proxy", new CooldownSettings.RepoTypeConfig(false, Duration.ofHours(24)))
        );
        MatcherAssert.assertThat(
            "per-type disabled beats global enabled when no per-name",
            settings.effectiveEnabled("npm-proxy", "any-name"),
            new IsEqual<>(false)
        );
    }

    @Test
    void perTypeOverrideBeatsGlobalWhenPerNameAbsentForDuration() {
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofHours(72),
            Map.of("npm-proxy", new CooldownSettings.RepoTypeConfig(true, Duration.ofHours(24)))
        );
        MatcherAssert.assertThat(
            "per-type 24h duration beats global 72h when no per-name",
            settings.effectiveMinimumAllowedAge("npm-proxy", "any-name"),
            new IsEqual<>(Duration.ofHours(24))
        );
    }

    @Test
    void globalWinsWhenNeitherOverrideSetForEnabled() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofHours(72));
        MatcherAssert.assertThat(
            "global enabled when no overrides exist",
            settings.effectiveEnabled("docker-proxy", "no-such-repo"),
            new IsEqual<>(true)
        );
    }

    @Test
    void globalWinsWhenNeitherOverrideSetForDuration() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofHours(72));
        MatcherAssert.assertThat(
            "global 72h duration returned when no overrides exist",
            settings.effectiveMinimumAllowedAge("docker-proxy", "no-such-repo"),
            new IsEqual<>(Duration.ofHours(72))
        );
    }

    @Test
    void nullRepoNameTreatedAsNoPerNameOverrideForEnabled() {
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofHours(72),
            Map.of("maven-proxy", new CooldownSettings.RepoTypeConfig(false, Duration.ofDays(30)))
        );
        MatcherAssert.assertThat(
            "null repoName falls through to per-type",
            settings.effectiveEnabled("maven-proxy", null),
            new IsEqual<>(false)
        );
    }

    @Test
    void nullRepoNameTreatedAsNoPerNameOverrideForDuration() {
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofHours(72),
            Map.of("maven-proxy", new CooldownSettings.RepoTypeConfig(true, Duration.ofDays(30)))
        );
        MatcherAssert.assertThat(
            "null repoName falls through to per-type duration",
            settings.effectiveMinimumAllowedAge("maven-proxy", null),
            new IsEqual<>(Duration.ofDays(30))
        );
    }

    @Test
    void perNameOverrideUsedEvenWhenRepoTypeHasNoOverride() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofHours(72));
        settings.setRepoNameOverride("my-special-repo", true, Duration.ofDays(14));
        MatcherAssert.assertThat(
            "per-name override applies regardless of per-type tier presence",
            settings.effectiveMinimumAllowedAge("maven-proxy", "my-special-repo"),
            new IsEqual<>(Duration.ofDays(14))
        );
    }
}
