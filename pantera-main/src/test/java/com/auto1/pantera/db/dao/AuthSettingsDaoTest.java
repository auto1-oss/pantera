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
package com.auto1.pantera.db.dao;

import java.util.Map;
import java.util.Optional;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testcontainers-based integration tests for {@link AuthSettingsDao}.
 * @since 2.0.0
 */
@Testcontainers
class AuthSettingsDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;

    AuthSettingsDao dao;

    @BeforeAll
    static void setup() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(2);
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
        this.dao = new AuthSettingsDao(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("TRUNCATE TABLE auth_settings");
            conn.createStatement().execute(
                String.join(" ",
                    "INSERT INTO auth_settings (key, value) VALUES",
                    "('access_token_ttl_seconds', '3600'),",
                    "('api_token_allow_permanent', 'true')",
                    "ON CONFLICT (key) DO NOTHING"
                )
            );
        }
    }

    @Test
    void getsExistingValue() {
        final Optional<String> result = this.dao.get("access_token_ttl_seconds");
        assertThat(result.isPresent(), is(true));
        assertThat(result.get(), is("3600"));
    }

    @Test
    void returnsEmptyForMissing() {
        final Optional<String> result = this.dao.get("no_such_key");
        assertThat(result.isPresent(), is(false));
    }

    @Test
    void getsIntWithDefault() {
        assertThat(this.dao.getInt("access_token_ttl_seconds", 0), is(3600));
        assertThat(this.dao.getInt("no_such_key", 42), is(42));
    }

    @Test
    void getsBoolWithDefault() {
        assertThat(this.dao.getBool("api_token_allow_permanent", false), is(true));
        assertThat(this.dao.getBool("no_such_key", true), is(true));
    }

    @Test
    void putsAndGets() {
        this.dao.put("new_setting", "hello");
        final Optional<String> result = this.dao.get("new_setting");
        assertThat(result.isPresent(), is(true));
        assertThat(result.get(), is("hello"));
    }

    @Test
    void putUpdatesExisting() {
        this.dao.put("access_token_ttl_seconds", "7200");
        assertThat(this.dao.get("access_token_ttl_seconds").get(), is("7200"));
    }

    @Test
    void getsAll() {
        final Map<String, String> all = this.dao.getAll();
        assertThat(all.size(), is(2));
        assertThat(all, hasKey("access_token_ttl_seconds"));
        assertThat(all, hasKey("api_token_allow_permanent"));
        assertThat(all.get("access_token_ttl_seconds"), is("3600"));
    }
}
