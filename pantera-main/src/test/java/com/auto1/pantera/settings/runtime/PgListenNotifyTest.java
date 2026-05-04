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
import java.util.concurrent.atomic.AtomicReference;
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
 * Tests for the long-lived LISTEN settings_changed worker that delivers
 * NOTIFY payloads (emitted by the V127 trigger) to a SettingsChangeListener.
 */
@Testcontainers
final class PgListenNotifyTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;

    private SettingsDao dao;
    private PgListenNotify pln;

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
        if (this.pln != null) {
            this.pln.stop();
        }
    }

    @Test
    void deliversNotifyPayloadToListener() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> received = new AtomicReference<>();
        this.pln = new PgListenNotify(ds, key -> {
            received.set(key);
            latch.countDown();
        });
        this.pln.start();
        assertTrue(
            this.pln.awaitListening(Duration.ofSeconds(2)),
            "worker did not register LISTEN within 2 seconds"
        );
        this.dao.put(
            "delta",
            Json.createObjectBuilder().add("value", 1).build(),
            "test"
        );
        final boolean delivered = latch.await(2, TimeUnit.SECONDS);
        assertThat("listener should be invoked within 2s", delivered, is(true));
        assertThat(received.get(), equalTo("delta"));
    }
}
