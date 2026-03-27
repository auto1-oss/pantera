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
package com.auto1.pantera.npm.cooldown;

import com.auto1.pantera.cooldown.CooldownDependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link NpmCooldownInspector}.
 *
 * @since 1.0
 */
final class NpmCooldownInspectorTest {

    private NpmCooldownInspector inspector;

    @BeforeEach
    void setUp() {
        this.inspector = new NpmCooldownInspector();
    }

    @Test
    void returnsPreloadedReleaseDate() throws Exception {
        final Instant releaseDate = Instant.parse("2023-06-15T12:00:00Z");
        final Map<String, Instant> dates = new HashMap<>();
        dates.put("1.0.0", releaseDate);
        dates.put("2.0.0", Instant.parse("2023-07-01T00:00:00Z"));

        this.inspector.preloadReleaseDates(dates);

        final Optional<Instant> result = this.inspector.releaseDate("test-package", "1.0.0").get();

        assertThat(result.isPresent(), is(true));
        assertThat(result.get(), equalTo(releaseDate));
    }

    @Test
    void returnsEmptyWhenNotPreloaded() throws Exception {
        final Optional<Instant> result = this.inspector.releaseDate("test-package", "1.0.0").get();

        assertThat(result.isPresent(), is(false));
    }

    @Test
    void returnsEmptyAfterClear() throws Exception {
        final Map<String, Instant> dates = new HashMap<>();
        dates.put("1.0.0", Instant.now());

        this.inspector.preloadReleaseDates(dates);
        this.inspector.clearPreloadedDates();

        final Optional<Instant> result = this.inspector.releaseDate("test-package", "1.0.0").get();

        assertThat(result.isPresent(), is(false));
    }

    @Test
    void preloadReplacesExistingDates() throws Exception {
        final Instant first = Instant.parse("2023-01-01T00:00:00Z");
        final Instant second = Instant.parse("2023-06-01T00:00:00Z");

        final Map<String, Instant> firstDates = new HashMap<>();
        firstDates.put("1.0.0", first);
        this.inspector.preloadReleaseDates(firstDates);

        final Map<String, Instant> secondDates = new HashMap<>();
        secondDates.put("1.0.0", second);
        this.inspector.preloadReleaseDates(secondDates);

        final Optional<Instant> result = this.inspector.releaseDate("test-package", "1.0.0").get();

        assertThat(result.isPresent(), is(true));
        assertThat(result.get(), equalTo(second));
    }

    @Test
    void returnsEmptyDependencies() throws Exception {
        final List<CooldownDependency> deps = 
            this.inspector.dependencies("test-package", "1.0.0").get();

        assertThat(deps, is(empty()));
    }

    @Test
    void tracksPreloadedCount() {
        assertThat(this.inspector.preloadedCount(), equalTo(0));

        final Map<String, Instant> dates = new HashMap<>();
        dates.put("1.0.0", Instant.now());
        dates.put("2.0.0", Instant.now());
        dates.put("3.0.0", Instant.now());

        this.inspector.preloadReleaseDates(dates);

        assertThat(this.inspector.preloadedCount(), equalTo(3));
    }

    @Test
    void checksIfVersionIsPreloaded() {
        final Map<String, Instant> dates = new HashMap<>();
        dates.put("1.0.0", Instant.now());

        this.inspector.preloadReleaseDates(dates);

        assertThat(this.inspector.hasPreloaded("1.0.0"), is(true));
        assertThat(this.inspector.hasPreloaded("2.0.0"), is(false));
    }

    @Test
    void handlesMultipleVersions() throws Exception {
        final Map<String, Instant> dates = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            dates.put(String.format("%d.0.0", i), Instant.now().minusSeconds(i * 86400));
        }

        this.inspector.preloadReleaseDates(dates);

        assertThat(this.inspector.preloadedCount(), equalTo(100));

        for (int i = 0; i < 100; i++) {
            final String version = String.format("%d.0.0", i);
            final Optional<Instant> result = this.inspector.releaseDate("pkg", version).get();
            assertThat(result.isPresent(), is(true));
        }
    }
}
