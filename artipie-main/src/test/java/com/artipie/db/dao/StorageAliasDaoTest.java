/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.db.dao;

import java.util.List;
import javax.json.Json;
import javax.json.JsonObject;
import javax.sql.DataSource;
import com.artipie.db.DbManager;
import com.artipie.db.PostgreSQLTestConfig;
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
class StorageAliasDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();
    static HikariDataSource ds;
    StorageAliasDao dao;

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
        this.dao = new StorageAliasDao(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM storage_aliases");
            conn.createStatement().execute("DELETE FROM repositories");
        }
    }

    @Test
    void putsAndListsGlobalAlias() {
        final JsonObject config = Json.createObjectBuilder()
            .add("type", "fs").add("path", "/var/artipie/data").build();
        this.dao.put("default", null, config);
        final List<JsonObject> globals = this.dao.listGlobal();
        assertEquals(1, globals.size());
        assertEquals("default", globals.get(0).getString("name"));
        assertEquals("fs", globals.get(0).getJsonObject("config").getString("type"));
    }

    @Test
    void putsAndListsRepoAlias() {
        final JsonObject config = Json.createObjectBuilder()
            .add("type", "s3").add("bucket", "my-bucket").build();
        this.dao.put("s3-store", "maven-central", config);
        final List<JsonObject> repoAliases = this.dao.listForRepo("maven-central");
        assertEquals(1, repoAliases.size());
        assertEquals("s3-store", repoAliases.get(0).getString("name"));
    }

    @Test
    void updatesExistingAlias() {
        final JsonObject orig = Json.createObjectBuilder()
            .add("type", "fs").add("path", "/old").build();
        this.dao.put("default", null, orig);
        final JsonObject updated = Json.createObjectBuilder()
            .add("type", "fs").add("path", "/new").build();
        this.dao.put("default", null, updated);
        final List<JsonObject> globals = this.dao.listGlobal();
        assertEquals(1, globals.size());
        assertEquals("/new", globals.get(0).getJsonObject("config").getString("path"));
    }

    @Test
    void deletesGlobalAlias() {
        this.dao.put("temp", null, Json.createObjectBuilder().add("type", "fs").build());
        assertEquals(1, this.dao.listGlobal().size());
        this.dao.delete("temp", null);
        assertEquals(0, this.dao.listGlobal().size());
    }

    @Test
    void deletesRepoAlias() {
        this.dao.put("store", "my-repo", Json.createObjectBuilder().add("type", "fs").build());
        assertEquals(1, this.dao.listForRepo("my-repo").size());
        this.dao.delete("store", "my-repo");
        assertEquals(0, this.dao.listForRepo("my-repo").size());
    }

    @Test
    void findReposUsingAlias() throws Exception {
        // Insert a repo whose config references alias "default"
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute(
                "INSERT INTO repositories (name, type, config) VALUES " +
                "('maven-central', 'maven-proxy', " +
                "'{\"repo\":{\"type\":\"maven-proxy\",\"storage\":\"default\"}}'::jsonb)"
            );
        }
        final List<String> repos = this.dao.findReposUsing("default");
        assertEquals(1, repos.size());
        assertEquals("maven-central", repos.get(0));
    }
}
