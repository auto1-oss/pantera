/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db.dao;

import java.util.List;
import javax.json.Json;
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
class AuthProviderDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();
    static HikariDataSource ds;
    AuthProviderDao dao;

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
        this.dao = new AuthProviderDao(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM auth_providers");
        }
    }

    @Test
    void putsAndListsProvider() {
        final JsonObject config = Json.createObjectBuilder()
            .add("realm", "pantera").build();
        this.dao.put("local", 1, config);
        final List<JsonObject> all = this.dao.list();
        assertEquals(1, all.size());
        assertEquals("local", all.get(0).getString("type"));
        assertEquals(1, all.get(0).getInt("priority"));
    }

    @Test
    void upsertsExistingProviderByType() {
        this.dao.put("keycloak", 1, Json.createObjectBuilder()
            .add("url", "http://old").build());
        this.dao.put("keycloak", 2, Json.createObjectBuilder()
            .add("url", "http://new").build());
        final List<JsonObject> all = this.dao.list();
        assertEquals(1, all.size());
        assertEquals(2, all.get(0).getInt("priority"));
        assertEquals("http://new",
            all.get(0).getJsonObject("config").getString("url"));
    }

    @Test
    void listsEnabledOnly() {
        this.dao.put("local", 1, Json.createObjectBuilder().build());
        this.dao.put("keycloak", 2, Json.createObjectBuilder().build());
        // Disable keycloak
        final int kcId = this.dao.list().stream()
            .filter(p -> p.getString("type").equals("keycloak"))
            .findFirst().get().getInt("id");
        this.dao.disable(kcId);
        final List<JsonObject> enabled = this.dao.listEnabled();
        assertEquals(1, enabled.size());
        assertEquals("local", enabled.get(0).getString("type"));
    }

    @Test
    void enablesAndDisablesProvider() {
        this.dao.put("okta", 1, Json.createObjectBuilder().build());
        final int id = this.dao.list().get(0).getInt("id");
        this.dao.disable(id);
        assertFalse(this.dao.list().get(0).getBoolean("enabled"));
        this.dao.enable(id);
        assertTrue(this.dao.list().get(0).getBoolean("enabled"));
    }

    @Test
    void deletesProvider() {
        this.dao.put("temp", 1, Json.createObjectBuilder().build());
        assertEquals(1, this.dao.list().size());
        final int id = this.dao.list().get(0).getInt("id");
        this.dao.delete(id);
        assertEquals(0, this.dao.list().size());
    }

    @Test
    void listsOrderedByPriority() {
        this.dao.put("keycloak", 2, Json.createObjectBuilder().build());
        this.dao.put("local", 1, Json.createObjectBuilder().build());
        final List<JsonObject> all = this.dao.list();
        assertEquals("local", all.get(0).getString("type"));
        assertEquals("keycloak", all.get(1).getString("type"));
    }
}
