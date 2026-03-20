/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db;

import javax.json.Json;
import javax.json.JsonObject;
import javax.sql.DataSource;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.db.dao.*;
import com.auto1.pantera.db.migration.YamlToDbMigrator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SettingsLayerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();
    static HikariDataSource ds;

    @TempDir
    Path configDir;

    @BeforeAll
    static void setup() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(3);
        ds = new HikariDataSource(cfg);
    }

    @AfterAll
    static void teardown() {
        if (ds != null) { ds.close(); }
    }

    @BeforeEach
    void clean() throws Exception {
        DbManager.migrate(ds);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM repositories");
            conn.createStatement().execute("DELETE FROM user_roles");
            conn.createStatement().execute("DELETE FROM users");
            conn.createStatement().execute("DELETE FROM roles");
            conn.createStatement().execute("DELETE FROM storage_aliases");
            conn.createStatement().execute("DELETE FROM settings");
            conn.createStatement().execute("DELETE FROM auth_providers");
            conn.createStatement().execute("DELETE FROM audit_log");
        }
    }

    @Test
    void flywayCreatesTables() throws Exception {
        try (var conn = ds.getConnection();
             var rs = conn.getMetaData().getTables(null, "public", "repositories", null)) {
            assertTrue(rs.next());
        }
    }

    @Test
    void migratorPopulatesFromYaml() throws Exception {
        final Path repos = this.configDir.resolve("repo");
        Files.createDirectories(repos);
        Files.writeString(repos.resolve("test-maven.yaml"),
            "repo:\n  type: maven-proxy\n  storage: default");
        final YamlToDbMigrator migrator = new YamlToDbMigrator(ds, this.configDir.resolve("security"), repos);
        assertTrue(migrator.migrate());
        final RepositoryDao repoDao = new RepositoryDao(ds);
        assertTrue(repoDao.exists(new RepositoryName.Simple("test-maven")));
    }

    @Test
    void crudOperationsWork() throws Exception {
        final RepositoryDao repoDao = new RepositoryDao(ds);
        repoDao.save(new RepositoryName.Simple("int-test-npm"),
            Json.createObjectBuilder()
                .add("repo", Json.createObjectBuilder()
                    .add("type", "npm-local")
                    .add("storage", "default"))
                .build(),
            "admin"
        );
        assertTrue(repoDao.exists(new RepositoryName.Simple("int-test-npm")));
        final AuditLogDao audit = new AuditLogDao(ds);
        audit.log("admin", "CREATE", "repository", "int-test-npm", null,
            Json.createObjectBuilder().add("type", "npm-local").build());
        final SettingsDao settings = new SettingsDao(ds);
        settings.put("int_test_key", Json.createObjectBuilder().add("v", 1).build(), "admin");
        assertTrue(settings.get("int_test_key").isPresent());
    }

    @Test
    void migratorIsIdempotent() throws Exception {
        final Path repos = this.configDir.resolve("repo");
        Files.createDirectories(repos);
        final YamlToDbMigrator migrator = new YamlToDbMigrator(ds, this.configDir.resolve("security"), repos);
        migrator.migrate();
        // Second call should skip
        assertFalse(migrator.migrate());
    }
}
