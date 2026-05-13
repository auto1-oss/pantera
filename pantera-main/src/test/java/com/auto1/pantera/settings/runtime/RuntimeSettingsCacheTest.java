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
package com.auto1.pantera.settings.runtime;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.db.dao.SettingsDao;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the hot-reloaded snapshot of runtime-tunable settings. Wraps
 * {@link SettingsDao}, owns a {@link PgListenNotify} worker for primary
 * refresh, and runs a 30-second polling fallback.
 */
@Testcontainers
final class RuntimeSettingsCacheTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;

    private SettingsDao dao;
    private RuntimeSettingsCache cache;

    @BeforeAll
    static void setup() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(3);
        ds = new HikariDataSource(cfg);
        DbManager.migrate(ds);
    }

    @AfterAll
    static void teardown() {
        if (ds != null) {
            ds.close();
        }
    }

    @BeforeEach
    void init() throws Exception {
        this.dao = new SettingsDao(ds);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM settings");
        }
    }

    @AfterEach
    void cleanup() {
        if (this.cache != null) {
            this.cache.stop();
        }
    }

    @Test
    void initialSnapshotUsesDefaultsWhenTableIsEmpty() {
        this.cache = new RuntimeSettingsCache(this.dao, ds);
        this.cache.start();
        assertThat(this.cache.httpTuning(), equalTo(HttpTuning.defaults()));
    }

    @Test
    void snapshotRefreshesOnNotify() throws Exception {
        this.cache = new RuntimeSettingsCache(this.dao, ds);
        this.cache.start();
        assertTrue(
            this.cache.awaitListening(Duration.ofSeconds(2)),
            "listener did not register LISTEN within 2 seconds"
        );
        final CountDownLatch latch = new CountDownLatch(1);
        this.cache.addListener("http_client.protocol", k -> latch.countDown());
        this.dao.put(
            "http_client.protocol",
            Json.createObjectBuilder().add("value", "h1").build(),
            "test"
        );
        final boolean fired = latch.await(3, TimeUnit.SECONDS);
        assertThat("subscriber should fire within 3s of NOTIFY", fired, is(true));
        // Subscriber fires AFTER rereadAll() returns, so the snapshot is already swapped.
        // Brief wait covers the case where the snapshot swap happens-before the latch
        // count-down is observed by the asserting thread on a different core.
        Thread.sleep(100);
        assertThat(
            this.cache.httpTuning().protocol(),
            equalTo(HttpTuning.Protocol.H1)
        );
    }
}
