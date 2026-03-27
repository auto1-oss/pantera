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

import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
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
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class UserDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();
    static HikariDataSource ds;
    UserDao dao;

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
        this.dao = new UserDao(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM user_roles");
            conn.createStatement().execute("DELETE FROM users");
            conn.createStatement().execute("DELETE FROM roles");
        }
    }

    @Test
    void addsAndGetsUser() {
        final JsonObject info = Json.createObjectBuilder()
            .add("pass", "secret123")
            .add("type", "plain")
            .add("email", "john@example.com")
            .build();
        this.dao.addOrUpdate(info, "john");
        final Optional<JsonObject> result = this.dao.get("john");
        assertTrue(result.isPresent());
        assertEquals("john@example.com", result.get().getString("email"));
        // Password should NOT be in get() result
        assertFalse(result.get().containsKey("pass"));
    }

    @Test
    void listsUsers() {
        addTestUser("alice");
        addTestUser("bob");
        final JsonArray list = this.dao.list();
        assertEquals(2, list.size());
    }

    @Test
    void updatesExistingUser() {
        addTestUser("charlie");
        final JsonObject updated = Json.createObjectBuilder()
            .add("email", "new@example.com")
            .add("pass", "newpass")
            .add("type", "plain")
            .build();
        this.dao.addOrUpdate(updated, "charlie");
        assertEquals("new@example.com", this.dao.get("charlie").get().getString("email"));
    }

    @Test
    void enablesAndDisablesUser() {
        addTestUser("dave");
        this.dao.disable("dave");
        assertFalse(this.dao.get("dave").get().getBoolean("enabled"));
        this.dao.enable("dave");
        assertTrue(this.dao.get("dave").get().getBoolean("enabled"));
    }

    @Test
    void removesUser() {
        addTestUser("eve");
        assertTrue(this.dao.get("eve").isPresent());
        this.dao.remove("eve");
        assertTrue(this.dao.get("eve").isEmpty());
    }

    @Test
    void altersPassword() {
        addTestUser("frank");
        final JsonObject passInfo = Json.createObjectBuilder()
            .add("new_pass", "updated_hash")
            .add("new_type", "sha256")
            .build();
        this.dao.alterPassword("frank", passInfo);
        // Verify internally that password was changed
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT password_hash FROM users WHERE username = ?")) {
            ps.setString(1, "frank");
            var rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals("updated_hash", rs.getString("password_hash"));
        } catch (final Exception ex) {
            fail(ex);
        }
    }

    @Test
    void returnsEmptyForMissingUser() {
        assertTrue(this.dao.get("nobody").isEmpty());
    }

    @Test
    void addsUserWithRoles() throws Exception {
        // Seed a role first
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute(
                "INSERT INTO roles (name, permissions) VALUES ('readers', '{}'::jsonb)"
            );
        }
        final JsonObject info = Json.createObjectBuilder()
            .add("pass", "secret")
            .add("type", "plain")
            .add("roles", Json.createArrayBuilder().add("readers"))
            .build();
        this.dao.addOrUpdate(info, "grace");
        final JsonObject user = this.dao.get("grace").get();
        assertTrue(user.getJsonArray("roles").getString(0).equals("readers"));
    }

    private void addTestUser(final String name) {
        this.dao.addOrUpdate(
            Json.createObjectBuilder()
                .add("pass", "pass123")
                .add("type", "plain")
                .add("email", name + "@example.com")
                .build(),
            name
        );
    }
}
