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

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.dao.SettingsDao;
import java.time.Duration;
import java.util.HashMap;
import javax.json.Json;
import javax.json.JsonObject;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies Task 8 wiring: {@code history_retention_days} and
 * {@code cleanup_batch_limit} round-trip through the DB settings blob
 * into {@link CooldownSettings} via
 * {@link CooldownSupport#loadDbCooldownSettings(CooldownSettings, DataSource)}.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class CooldownSettingsDbRoundTripTest {

    @Container
    @SuppressWarnings("unused")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:15");

    private DataSource dataSource;

    private SettingsDao settingsDao;

    @BeforeAll
    void initDb() {
        this.dataSource = new ArtifactDbFactory(this.settings(), "cooldowns").initialize();
        // Flyway migrations — V121 adds settings table helper functions used
        // by the cron path. The settings table itself exists from earlier
        // migrations; we only need to write JSONB into it for this test.
        DbManager.migrate(this.dataSource);
        this.settingsDao = new SettingsDao(this.dataSource);
    }

    @AfterEach
    void clearSettingsBlob() {
        this.settingsDao.delete("cooldown");
    }

    @Test
    void writesAndReloadsRetentionAndBatchLimit() {
        final JsonObject blob = Json.createObjectBuilder()
            .add("enabled", true)
            .add("minimum_allowed_age", "48h")
            .add("history_retention_days", 30)
            .add("cleanup_batch_limit", 5000)
            .build();
        this.settingsDao.put("cooldown", blob, "tester");
        final CooldownSettings csettings = new CooldownSettings(
            false, Duration.ofHours(CooldownSettings.DEFAULT_HOURS)
        );
        CooldownSupport.loadDbCooldownSettings(csettings, this.dataSource);
        MatcherAssert.assertThat(
            "enabled should reflect DB blob", csettings.enabled(), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "minimum_allowed_age should reflect DB blob",
            csettings.minimumAllowedAge(), Matchers.equalTo(Duration.ofHours(48))
        );
        MatcherAssert.assertThat(
            "history_retention_days should reflect DB blob",
            csettings.historyRetentionDays(), Matchers.is(30)
        );
        MatcherAssert.assertThat(
            "cleanup_batch_limit should reflect DB blob",
            csettings.cleanupBatchLimit(), Matchers.is(5000)
        );
    }

    @Test
    void appliesDefaultsWhenKeysAbsent() {
        // Blob deliberately omits the two new top-level keys. The loader must
        // preserve the in-memory defaults (90 days, 10000 rows) so that an
        // older blob written before Task 8 transitions gracefully.
        final JsonObject blob = Json.createObjectBuilder()
            .add("enabled", true)
            .add("minimum_allowed_age", "72h")
            .build();
        this.settingsDao.put("cooldown", blob, "tester");
        final CooldownSettings csettings = new CooldownSettings(
            true, Duration.ofHours(CooldownSettings.DEFAULT_HOURS)
        );
        CooldownSupport.loadDbCooldownSettings(csettings, this.dataSource);
        MatcherAssert.assertThat(
            "history_retention_days should preserve the default",
            csettings.historyRetentionDays(), Matchers.is(90)
        );
        MatcherAssert.assertThat(
            "cleanup_batch_limit should preserve the default",
            csettings.cleanupBatchLimit(), Matchers.is(10_000)
        );
    }

    @Test
    void outOfRangeValuesSwallowedAsLoadFailure() {
        // update() throws on out-of-range values; the outer catch logs it as
        // a DB-load failure, and the in-memory CooldownSettings stays at
        // whatever it was before the load attempt (here: the defaults).
        final JsonObject blob = Json.createObjectBuilder()
            .add("enabled", true)
            .add("minimum_allowed_age", "48h")
            .add("history_retention_days", 9999)
            .build();
        this.settingsDao.put("cooldown", blob, "tester");
        final CooldownSettings csettings = new CooldownSettings(
            true, Duration.ofHours(CooldownSettings.DEFAULT_HOURS)
        );
        CooldownSupport.loadDbCooldownSettings(csettings, this.dataSource);
        MatcherAssert.assertThat(
            "out-of-range retention must NOT be applied",
            csettings.historyRetentionDays(), Matchers.is(90)
        );
    }

    @Test
    void updateThrowsOnOutOfRangeRetention() {
        final CooldownSettings csettings = new CooldownSettings(
            true, Duration.ofHours(CooldownSettings.DEFAULT_HOURS)
        );
        Assertions.assertThrows(IllegalArgumentException.class, () -> csettings.update(
            true, Duration.ofHours(72), new HashMap<>(), 0, 10_000
        ));
        Assertions.assertThrows(IllegalArgumentException.class, () -> csettings.update(
            true, Duration.ofHours(72), new HashMap<>(), 3651, 10_000
        ));
    }

    @Test
    void updateThrowsOnOutOfRangeBatchLimit() {
        final CooldownSettings csettings = new CooldownSettings(
            true, Duration.ofHours(CooldownSettings.DEFAULT_HOURS)
        );
        Assertions.assertThrows(IllegalArgumentException.class, () -> csettings.update(
            true, Duration.ofHours(72), new HashMap<>(), 90, 0
        ));
        Assertions.assertThrows(IllegalArgumentException.class, () -> csettings.update(
            true, Duration.ofHours(72), new HashMap<>(), 90, 100_001
        ));
    }

    private YamlMapping settings() {
        return Yaml.createYamlMappingBuilder().add(
            "artifacts_database",
            Yaml.createYamlMappingBuilder()
                .add(ArtifactDbFactory.YAML_HOST, POSTGRES.getHost())
                .add(ArtifactDbFactory.YAML_PORT, String.valueOf(POSTGRES.getFirstMappedPort()))
                .add(ArtifactDbFactory.YAML_DATABASE, POSTGRES.getDatabaseName())
                .add(ArtifactDbFactory.YAML_USER, POSTGRES.getUsername())
                .add(ArtifactDbFactory.YAML_PASSWORD, POSTGRES.getPassword())
                .build()
        ).build();
    }
}
