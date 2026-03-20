/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db.dao;

import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.sql.DataSource;
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
class RoleDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();
    static HikariDataSource ds;
    RoleDao dao;

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
        if (ds != null) { ds.close(); }
    }

    @BeforeEach
    void init() throws Exception {
        this.dao = new RoleDao(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM user_roles");
            conn.createStatement().execute("DELETE FROM roles");
        }
    }

    @Test
    void addsAndGetsRole() {
        final JsonObject perms = Json.createObjectBuilder()
            .add("adapter_basic_permissions", Json.createObjectBuilder()
                .add("maven-repo", Json.createArrayBuilder().add("read").add("write")))
            .build();
        this.dao.addOrUpdate(perms, "developers");
        final Optional<JsonObject> result = this.dao.get("developers");
        assertTrue(result.isPresent());
        assertEquals("developers", result.get().getString("name"));
        assertTrue(result.get().containsKey("adapter_basic_permissions"));
    }

    @Test
    void listsRoles() {
        addTestRole("readers");
        addTestRole("writers");
        final JsonArray list = this.dao.list();
        assertEquals(2, list.size());
    }

    @Test
    void updatesExistingRole() {
        addTestRole("updatable");
        final JsonObject newPerms = Json.createObjectBuilder()
            .add("api_repository", Json.createArrayBuilder().add("read"))
            .build();
        this.dao.addOrUpdate(newPerms, "updatable");
        final JsonObject role = this.dao.get("updatable").get();
        assertTrue(role.containsKey("api_repository"));
    }

    @Test
    void enablesAndDisablesRole() {
        addTestRole("toggleable");
        this.dao.disable("toggleable");
        assertFalse(this.dao.get("toggleable").get().getBoolean("enabled"));
        this.dao.enable("toggleable");
        assertTrue(this.dao.get("toggleable").get().getBoolean("enabled"));
    }

    @Test
    void removesRole() {
        addTestRole("removable");
        assertTrue(this.dao.get("removable").isPresent());
        this.dao.remove("removable");
        assertTrue(this.dao.get("removable").isEmpty());
    }

    @Test
    void returnsEmptyForMissingRole() {
        assertTrue(this.dao.get("nonexistent").isEmpty());
    }

    private void addTestRole(final String name) {
        this.dao.addOrUpdate(
            Json.createObjectBuilder()
                .add("adapter_basic_permissions", Json.createObjectBuilder()
                    .add("*", Json.createArrayBuilder().add("read")))
                .build(),
            name
        );
    }
}
