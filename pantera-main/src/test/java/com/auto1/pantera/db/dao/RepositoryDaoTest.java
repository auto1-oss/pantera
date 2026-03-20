/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db.dao;

import java.util.Collection;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.api.RepositoryName;
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
class RepositoryDaoTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();
    static HikariDataSource ds;
    RepositoryDao dao;

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
        this.dao = new RepositoryDao(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM repositories");
        }
    }

    @Test
    void savesAndGetsRepository() {
        final JsonObject config = Json.createObjectBuilder()
            .add("repo", Json.createObjectBuilder()
                .add("type", "maven-proxy")
                .add("storage", "default"))
            .build();
        this.dao.save(new RepositoryName.Simple("maven-central"), config, "admin");
        final JsonStructure result = this.dao.value(new RepositoryName.Simple("maven-central"));
        assertNotNull(result);
        assertEquals("maven-proxy",
            result.asJsonObject().getJsonObject("repo").getString("type"));
    }

    @Test
    void existsReturnsTrueForExistingRepo() {
        saveTestRepo("my-repo", "maven-proxy");
        assertTrue(this.dao.exists(new RepositoryName.Simple("my-repo")));
    }

    @Test
    void existsReturnsFalseForMissingRepo() {
        assertFalse(this.dao.exists(new RepositoryName.Simple("no-such-repo")));
    }

    @Test
    void listsAllRepos() {
        saveTestRepo("repo-a", "maven-proxy");
        saveTestRepo("repo-b", "npm-local");
        final Collection<String> all = this.dao.listAll();
        assertEquals(2, all.size());
        assertTrue(all.contains("repo-a"));
        assertTrue(all.contains("repo-b"));
    }

    @Test
    void deletesRepository() {
        saveTestRepo("to-delete", "maven-local");
        assertTrue(this.dao.exists(new RepositoryName.Simple("to-delete")));
        this.dao.delete(new RepositoryName.Simple("to-delete"));
        assertFalse(this.dao.exists(new RepositoryName.Simple("to-delete")));
    }

    @Test
    void movesRepository() {
        saveTestRepo("old-name", "docker-proxy");
        this.dao.move(new RepositoryName.Simple("old-name"), new RepositoryName.Simple("new-name"));
        assertFalse(this.dao.exists(new RepositoryName.Simple("old-name")));
        assertTrue(this.dao.exists(new RepositoryName.Simple("new-name")));
    }

    @Test
    void updatesExistingRepo() {
        saveTestRepo("updatable", "maven-proxy");
        final JsonObject updated = Json.createObjectBuilder()
            .add("repo", Json.createObjectBuilder()
                .add("type", "maven-local")
                .add("storage", "s3"))
            .build();
        this.dao.save(new RepositoryName.Simple("updatable"), updated, "admin");
        final JsonStructure result = this.dao.value(new RepositoryName.Simple("updatable"));
        assertEquals("maven-local",
            result.asJsonObject().getJsonObject("repo").getString("type"));
    }

    @Test
    void throwsOnGetMissingRepo() {
        assertThrows(IllegalStateException.class,
            () -> this.dao.value(new RepositoryName.Simple("nope")));
    }

    private void saveTestRepo(final String name, final String type) {
        this.dao.save(
            new RepositoryName.Simple(name),
            Json.createObjectBuilder()
                .add("repo", Json.createObjectBuilder()
                    .add("type", type)
                    .add("storage", "default"))
                .build(),
            "admin"
        );
    }
}
