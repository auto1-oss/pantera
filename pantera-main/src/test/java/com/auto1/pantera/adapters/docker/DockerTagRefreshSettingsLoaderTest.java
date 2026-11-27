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
package com.auto1.pantera.adapters.docker;

import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.test.InMemoryAuthSettings;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link DockerTagRefreshSettingsLoader}: DB row, env var and
 * default tiers, invalid values, and the reload after a write.
 *
 * @since 2.2.15
 */
final class DockerTagRefreshSettingsLoaderTest {

    /**
     * Env var of the setting.
     */
    private static final String ENV = "PANTERA_DOCKER_TAG_REFRESH_SECONDS";

    @AfterEach
    void tearDown() {
        DockerTagRefreshSettingsLoader.uninstall();
    }

    @Test
    void databaseRowWinsOverEnvAndDefault() {
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            new InMemoryAuthSettings(Map.of(DockerTagRefreshSettingsLoader.KEY, "600")).dao(),
            Map.of(ENV, "120")::get
        );
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ofSeconds(600)));
    }

    @Test
    void zeroIsAValidAge() {
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            new InMemoryAuthSettings(Map.of(DockerTagRefreshSettingsLoader.KEY, "0")).dao(),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ZERO));
    }

    @Test
    void envAppliesWithoutARow() {
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            new InMemoryAuthSettings().dao(), Map.of(ENV, "120")::get
        );
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ofSeconds(120)));
    }

    @Test
    void envAppliesWithoutADatabase() {
        DockerTagRefreshSettingsLoader.install(null, Map.of(ENV, "300")::get);
        MatcherAssert.assertThat(
            DockerTagRefreshSettingsLoader.activeSupplier().get(),
            new IsEqual<>(Duration.ofSeconds(300))
        );
    }

    @Test
    void defaultIsOneHour() {
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            new InMemoryAuthSettings().dao(), Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ofHours(1)));
    }

    @Test
    void uninstalledSupplierResolvesTheDefault() {
        MatcherAssert.assertThat(
            DockerTagRefreshSettingsLoader.activeSupplier().get(),
            new IsEqual<>(Duration.ofSeconds(DockerTagRefreshSettingsLoader.DEFAULT_SECONDS))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "31536001", "1h", "2.5", ""})
    void invalidRowFallsBackToEnv(final String value) {
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            new InMemoryAuthSettings(Map.of(DockerTagRefreshSettingsLoader.KEY, value)).dao(),
            Map.of(ENV, "120")::get
        );
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ofSeconds(120)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "31536001", "soon"})
    void invalidEnvFallsBackToDefault(final String value) {
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            null, Map.of(ENV, value)::get
        );
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ofHours(1)));
    }

    @Test
    void largestAgeIsAccepted() {
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            null, Map.of(ENV, "31536000")::get
        );
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ofDays(365)));
    }

    @Test
    void unreadableDatabaseFallsBackToEnv() {
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            new AuthSettingsDao(
                (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                    (target, method, args) -> {
                        throw new java.sql.SQLException("database down");
                    }
                )
            ),
            Map.of(ENV, "120")::get
        );
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ofSeconds(120)));
    }

    @Test
    void invalidateReadsTheNewRow() {
        final InMemoryAuthSettings table = new InMemoryAuthSettings(
            Map.of(DockerTagRefreshSettingsLoader.KEY, "600")
        );
        DockerTagRefreshSettingsLoader.install(table.dao(), Map.<String, String>of()::get);
        table.rows().put(DockerTagRefreshSettingsLoader.KEY, "60");
        MatcherAssert.assertThat(
            "the value read at install is kept until a reload",
            DockerTagRefreshSettingsLoader.activeSupplier().get(),
            new IsEqual<>(Duration.ofSeconds(600))
        );
        DockerTagRefreshSettingsLoader.installed().invalidate();
        MatcherAssert.assertThat(
            "a reload reads the new row",
            DockerTagRefreshSettingsLoader.activeSupplier().get(),
            new IsEqual<>(Duration.ofSeconds(60))
        );
    }

    @Test
    void refreshReadsARowAnotherNodeChanged() {
        final InMemoryAuthSettings table = new InMemoryAuthSettings(
            Map.of(DockerTagRefreshSettingsLoader.KEY, "600")
        );
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            table.dao(), Map.<String, String>of()::get
        );
        loader.get();
        table.rows().put(DockerTagRefreshSettingsLoader.KEY, "60");
        loader.refresh();
        MatcherAssert.assertThat(loader.get(), new IsEqual<>(Duration.ofSeconds(60)));
    }

    @Test
    void refreshKeepsTheAgeWhileTheDatabaseIsDown() {
        final InMemoryAuthSettings table = new InMemoryAuthSettings(
            Map.of(DockerTagRefreshSettingsLoader.KEY, "600")
        );
        final DockerTagRefreshSettingsLoader loader = new DockerTagRefreshSettingsLoader(
            table.dao(), Map.of(ENV, "120")::get
        );
        loader.get();
        table.down(true);
        loader.refresh();
        MatcherAssert.assertThat(
            "a failed re-read neither falls back to the env var nor to the default",
            loader.get(), new IsEqual<>(Duration.ofSeconds(600))
        );
        table.down(false);
        table.rows().remove(DockerTagRefreshSettingsLoader.KEY);
        loader.refresh();
        MatcherAssert.assertThat(
            "a row removed while the database is up hands over to the env var",
            loader.get(), new IsEqual<>(Duration.ofSeconds(120))
        );
    }

    @Test
    void installReadsTheValueAtOnce() {
        final InMemoryAuthSettings table = new InMemoryAuthSettings(
            Map.of(DockerTagRefreshSettingsLoader.KEY, "900")
        );
        DockerTagRefreshSettingsLoader.install(table.dao(), Map.<String, String>of()::get);
        table.rows().clear();
        MatcherAssert.assertThat(
            DockerTagRefreshSettingsLoader.activeSupplier().get(),
            new IsEqual<>(Duration.ofSeconds(900))
        );
    }
}
