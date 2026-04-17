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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

final class CooldownSettingsTest {

    @Test
    void globalDefaultsApplyWhenNoOverrides() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofHours(72));
        assertThat(settings.enabledFor("maven-proxy"), is(true));
        assertThat(settings.minimumAllowedAgeFor("maven-proxy"), equalTo(Duration.ofHours(72)));
    }

    @Test
    void repoTypeOverrideTakesPrecedenceOverGlobal() {
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofHours(72),
            Map.of("npm-proxy", new CooldownSettings.RepoTypeConfig(false, Duration.ofHours(24)))
        );
        assertThat("npm-proxy type override disables cooldown", settings.enabledFor("npm-proxy"), is(false));
        assertThat("maven-proxy falls back to global", settings.enabledFor("maven-proxy"), is(true));
    }

    @Test
    void repoNameOverrideNotPresentByDefault() {
        final CooldownSettings settings = CooldownSettings.defaults();
        assertThat(settings.isRepoNameOverridePresent("my-pypi"), is(false));
    }

    @Test
    void setRepoNameOverrideRegistersEntry() {
        final CooldownSettings settings = CooldownSettings.defaults();
        settings.setRepoNameOverride("my-pypi", true, Duration.ofDays(30));
        assertThat(settings.isRepoNameOverridePresent("my-pypi"), is(true));
        assertThat(settings.enabledForRepoName("my-pypi"), is(true));
        assertThat(settings.minimumAllowedAgeForRepoName("my-pypi"), equalTo(Duration.ofDays(30)));
    }

    @Test
    void repoNameOverrideDurationBeatsRepoTypeOverride() {
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofHours(72),
            Map.of("pypi-proxy", new CooldownSettings.RepoTypeConfig(true, Duration.ofHours(48)))
        );
        settings.setRepoNameOverride("my-pypi", true, Duration.ofDays(30));
        // Repo-name check (explicit call)
        assertThat(
            "repo-name override returns 30 days",
            settings.minimumAllowedAgeForRepoName("my-pypi"),
            equalTo(Duration.ofDays(30))
        );
        // Type-level still returns 48h for another repo of the same type
        assertThat(
            "per-type still returns 48h when no per-name override",
            settings.minimumAllowedAgeFor("pypi-proxy"),
            equalTo(Duration.ofHours(48))
        );
    }

    @Test
    void repoNameOverrideDisabledBeatsGlobalEnabled() {
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofHours(72));
        settings.setRepoNameOverride("internal-npm", false, Duration.ofHours(72));
        assertThat(settings.isRepoNameOverridePresent("internal-npm"), is(true));
        assertThat(settings.enabledForRepoName("internal-npm"), is(false));
    }

    @Test
    void setRepoNameOverrideIsIdempotentAndUpdateable() {
        final CooldownSettings settings = CooldownSettings.defaults();
        settings.setRepoNameOverride("repo-a", true, Duration.ofDays(7));
        settings.setRepoNameOverride("repo-a", true, Duration.ofDays(14));
        assertThat(settings.minimumAllowedAgeForRepoName("repo-a"), equalTo(Duration.ofDays(14)));
    }
}
