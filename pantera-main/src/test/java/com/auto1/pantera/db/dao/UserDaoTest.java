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
        // Verify password was bcrypt-hashed (not stored as plaintext)
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT password_hash FROM users WHERE username = ?")) {
            ps.setString(1, "frank");
            var rs = ps.executeQuery();
            assertTrue(rs.next());
            final String stored = rs.getString("password_hash");
            assertTrue(stored.startsWith("$2a$"), "should be bcrypt hash");
            assertTrue(
                org.mindrot.jbcrypt.BCrypt.checkpw("updated_hash", stored),
                "bcrypt hash should verify against original password"
            );
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

    @Test
    void listPagedFiltersUsers() {
        addTestUser("admin");
        addTestUser("alice");
        addTestUser("bob");
        final PagedResult<JsonObject> result =
            this.dao.listPaged("admin", "username", true, 20, 0);
        assertEquals(1, result.total());
        assertEquals(1, result.items().size());
        assertEquals("admin", result.items().get(0).getString("name"));
    }

    @Test
    void listPagedReturnsAllWhenNoQuery() {
        addTestUser("alice");
        addTestUser("bob");
        final PagedResult<JsonObject> result =
            this.dao.listPaged(null, "username", true, 20, 0);
        assertEquals(2, result.total());
        assertEquals(2, result.items().size());
    }

    @Test
    void listPagedSortsByEmailDescending() {
        addTestUserWithEmail("zara", "zara@example.com");
        addTestUserWithEmail("anna", "anna@example.com");
        addTestUserWithEmail("mike", "mike@example.com");
        final PagedResult<JsonObject> result =
            this.dao.listPaged(null, "email", false, 20, 0);
        assertEquals(3, result.total());
        assertEquals("zara@example.com", result.items().get(0).getString("email"));
        assertEquals("mike@example.com", result.items().get(1).getString("email"));
        assertEquals("anna@example.com", result.items().get(2).getString("email"));
    }

    @Test
    void listPagedPaginatesWithCorrectTotal() {
        addTestUser("user1");
        addTestUser("user2");
        addTestUser("user3");
        addTestUser("user4");
        addTestUser("user5");
        final PagedResult<JsonObject> page1 =
            this.dao.listPaged(null, "username", true, 2, 0);
        assertEquals(5, page1.total());
        assertEquals(2, page1.items().size());
        final PagedResult<JsonObject> page2 =
            this.dao.listPaged(null, "username", true, 2, 2);
        assertEquals(5, page2.total());
        assertEquals(2, page2.items().size());
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

    private void addTestUserWithEmail(final String name, final String email) {
        this.dao.addOrUpdate(
            Json.createObjectBuilder()
                .add("pass", "pass123")
                .add("type", "plain")
                .add("email", email)
                .build(),
            name
        );
    }
}
