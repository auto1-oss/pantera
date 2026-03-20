/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db.migration;

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.db.dao.AuthProviderDao;
import com.auto1.pantera.db.dao.RoleDao;
import com.auto1.pantera.db.dao.RepositoryDao;
import com.auto1.pantera.db.dao.SettingsDao;
import com.auto1.pantera.db.dao.UserDao;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link YamlToDbMigrator}.
 * @since 1.0
 */
@Testcontainers
class YamlToDbMigratorTest {

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
    void clean() throws Exception {
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM user_roles");
            conn.createStatement().execute("DELETE FROM repositories");
            conn.createStatement().execute("DELETE FROM users");
            conn.createStatement().execute("DELETE FROM roles");
            conn.createStatement().execute("DELETE FROM storage_aliases");
            conn.createStatement().execute("DELETE FROM settings");
            conn.createStatement().execute("DELETE FROM auth_providers");
        }
    }

    @Test
    void migratesRepoConfigs() throws Exception {
        final Path repos = this.configDir.resolve("repo");
        Files.createDirectories(repos);
        Files.writeString(
            repos.resolve("maven-central.yaml"),
            String.join(
                "\n",
                "repo:",
                "  type: maven-proxy",
                "  remotes:",
                "    - url: https://repo1.maven.org/maven2",
                "      cache:",
                "        storage: default",
                "  storage: default"
            )
        );
        final YamlToDbMigrator migrator = new YamlToDbMigrator(
            ds, this.configDir.resolve("security"), repos
        );
        migrator.migrate();
        final RepositoryDao dao = new RepositoryDao(ds);
        assertTrue(dao.exists(new RepositoryName.Simple("maven-central")));
        // Verify YAML sequences (remotes array) were preserved
        final var config = dao.value(
            new RepositoryName.Simple("maven-central")
        ).asJsonObject();
        assertTrue(config.getJsonObject("repo").containsKey("remotes"));
    }

    @Test
    void migratesUsersWithRoles() throws Exception {
        // Seed a role first
        final Path rolesDir = this.configDir.resolve("security").resolve("roles");
        Files.createDirectories(rolesDir);
        Files.writeString(
            rolesDir.resolve("readers.yaml"),
            String.join(
                "\n",
                "adapter_basic_permissions:",
                "  \"*\":",
                "    - read"
            )
        );
        final Path usersDir = this.configDir.resolve("security").resolve("users");
        Files.createDirectories(usersDir);
        Files.writeString(
            usersDir.resolve("john.yaml"),
            String.join(
                "\n",
                "type: plain",
                "pass: secret123",
                "email: john@example.com",
                "enabled: true",
                "roles:",
                "  - readers"
            )
        );
        final Path repos = this.configDir.resolve("repo");
        Files.createDirectories(repos);
        final YamlToDbMigrator migrator = new YamlToDbMigrator(
            ds, this.configDir.resolve("security"), repos
        );
        migrator.migrate();
        final UserDao userDao = new UserDao(ds);
        assertTrue(userDao.get("john").isPresent());
        // Verify password was NOT stored as plaintext
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT password_hash FROM users WHERE username = 'john'"
             )) {
            final var rs = ps.executeQuery();
            assertTrue(rs.next());
            // Password should be bcrypt-hashed (starts with $2)
            assertTrue(rs.getString("password_hash").startsWith("$2"));
        }
        // Verify role assignment
        final var user = userDao.get("john").get();
        assertEquals(1, user.getJsonArray("roles").size());
        assertEquals("readers", user.getJsonArray("roles").getString(0));
    }

    @Test
    void migratesRoles() throws Exception {
        final Path rolesDir = this.configDir.resolve("security").resolve("roles");
        Files.createDirectories(rolesDir);
        Files.writeString(
            rolesDir.resolve("devs.yaml"),
            String.join(
                "\n",
                "adapter_basic_permissions:",
                "  maven-repo:",
                "    - read",
                "    - write"
            )
        );
        final Path repos = this.configDir.resolve("repo");
        Files.createDirectories(repos);
        final YamlToDbMigrator migrator = new YamlToDbMigrator(
            ds, this.configDir.resolve("security"), repos
        );
        migrator.migrate();
        final RoleDao roleDao = new RoleDao(ds);
        assertTrue(roleDao.get("devs").isPresent());
    }

    @Test
    void migratesSettingsFromPanteraYml() throws Exception {
        Files.writeString(
            this.configDir.resolve("pantera.yml"),
            String.join(
                "\n",
                "meta:",
                "  layout: flat",
                "  credentials:",
                "    - type: artipie",
                "    - type: keycloak",
                "      url: http://keycloak:8080",
                "      realm: artipie"
            )
        );
        final Path repos = this.configDir.resolve("repo");
        Files.createDirectories(repos);
        final YamlToDbMigrator migrator = new YamlToDbMigrator(
            ds, this.configDir.resolve("security"), repos,
            this.configDir.resolve("pantera.yml")
        );
        migrator.migrate();
        // Verify settings
        final SettingsDao settings = new SettingsDao(ds);
        assertTrue(settings.get("layout").isPresent());
        // Verify auth providers
        final AuthProviderDao authDao = new AuthProviderDao(ds);
        assertEquals(2, authDao.list().size());
    }

    @Test
    void skipsIfAlreadyMigrated() throws Exception {
        final SettingsDao settings = new SettingsDao(ds);
        settings.put(
            "migration_completed",
            Json.createObjectBuilder()
                .add("completed", true)
                .add("version", 3)
                .build(),
            "system"
        );
        final YamlToDbMigrator migrator = new YamlToDbMigrator(
            ds, this.configDir.resolve("security"), this.configDir.resolve("repo")
        );
        assertFalse(migrator.migrate());
    }

    @Test
    void setsMigrationFlag() throws Exception {
        final Path repos = this.configDir.resolve("repo");
        Files.createDirectories(repos);
        final YamlToDbMigrator migrator = new YamlToDbMigrator(
            ds, this.configDir.resolve("security"), repos
        );
        migrator.migrate();
        final SettingsDao settings = new SettingsDao(ds);
        assertTrue(settings.get("migration_completed").isPresent());
    }
}
