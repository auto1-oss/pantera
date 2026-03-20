/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.security.policy;

import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.api.perms.ApiSearchPermission;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.perms.UserPermissions;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CachedDbPolicy}.
 * @since 1.21
 */
@Testcontainers
class CachedDbPolicyTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    static HikariDataSource ds;
    CachedDbPolicy policy;

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
        this.policy = new CachedDbPolicy(ds);
        try (Connection conn = ds.getConnection()) {
            conn.createStatement().execute("DELETE FROM user_roles");
            conn.createStatement().execute("DELETE FROM users");
            conn.createStatement().execute("DELETE FROM roles");
        }
    }

    @Test
    void grantsSearchPermissionFromRole() throws Exception {
        createRole("reader", Json.createObjectBuilder()
            .add("permissions", Json.createObjectBuilder()
                .add("api_search_permissions", Json.createArrayBuilder().add("read"))
                .add("api_repository_permissions", Json.createArrayBuilder().add("read"))
            ).build().toString()
        );
        createUser("alice", true);
        assignRole("alice", "reader");
        final UserPermissions perms = this.policy.getPermissions(
            new AuthUser("alice", "local")
        );
        assertTrue(
            perms.implies(new ApiSearchPermission(ApiSearchPermission.SearchAction.READ)),
            "User with reader role should have search read permission"
        );
        assertTrue(
            perms.implies(new ApiRepositoryPermission(
                ApiRepositoryPermission.RepositoryAction.READ
            )),
            "User with reader role should have repository read permission"
        );
    }

    @Test
    void deniesUnassignedPermission() throws Exception {
        createRole("reader", Json.createObjectBuilder()
            .add("permissions", Json.createObjectBuilder()
                .add("api_search_permissions", Json.createArrayBuilder().add("read"))
            ).build().toString()
        );
        createUser("bob", true);
        assignRole("bob", "reader");
        final UserPermissions perms = this.policy.getPermissions(
            new AuthUser("bob", "local")
        );
        assertFalse(
            perms.implies(new ApiRepositoryPermission(
                ApiRepositoryPermission.RepositoryAction.DELETE
            )),
            "User should not have repository delete permission"
        );
    }

    @Test
    void deniesDisabledUser() throws Exception {
        createRole("admin", Json.createObjectBuilder()
            .add("permissions", Json.createObjectBuilder()
                .add("api_search_permissions", Json.createArrayBuilder().add("read"))
            ).build().toString()
        );
        createUser("disabled_user", false);
        assignRole("disabled_user", "admin");
        final UserPermissions perms = this.policy.getPermissions(
            new AuthUser("disabled_user", "local")
        );
        assertFalse(
            perms.implies(new ApiSearchPermission(ApiSearchPermission.SearchAction.READ)),
            "Disabled user should not have any permissions"
        );
    }

    @Test
    void deniesDisabledRole() throws Exception {
        createRole("suspended", Json.createObjectBuilder()
            .add("permissions", Json.createObjectBuilder()
                .add("api_search_permissions", Json.createArrayBuilder().add("read"))
            ).build().toString()
        );
        disableRole("suspended");
        createUser("charlie", true);
        assignRole("charlie", "suspended");
        final UserPermissions perms = this.policy.getPermissions(
            new AuthUser("charlie", "local")
        );
        assertFalse(
            perms.implies(new ApiSearchPermission(ApiSearchPermission.SearchAction.READ)),
            "User with disabled role should not have permissions from that role"
        );
    }

    @Test
    void grantsAdapterPermissionFromRole() throws Exception {
        createRole("go_reader", Json.createObjectBuilder()
            .add("permissions", Json.createObjectBuilder()
                .add("adapter_basic_permissions", Json.createObjectBuilder()
                    .add("go", Json.createArrayBuilder().add("read")))
            ).build().toString()
        );
        createUser("dave", true);
        assignRole("dave", "go_reader");
        final UserPermissions perms = this.policy.getPermissions(
            new AuthUser("dave", "local")
        );
        assertTrue(
            perms.implies(new AdapterBasicPermission("go", "read")),
            "User should have read permission on go repo"
        );
        assertFalse(
            perms.implies(new AdapterBasicPermission("go", "write")),
            "User should not have write permission on go repo"
        );
    }

    @Test
    void invalidationClearsCache() throws Exception {
        createRole("mutable", Json.createObjectBuilder()
            .add("permissions", Json.createObjectBuilder()
                .add("api_search_permissions", Json.createArrayBuilder().add("read"))
            ).build().toString()
        );
        createUser("eve", true);
        assignRole("eve", "mutable");
        // Load into cache
        assertTrue(
            this.policy.getPermissions(new AuthUser("eve", "local"))
                .implies(new ApiSearchPermission(ApiSearchPermission.SearchAction.READ))
        );
        // Remove role permissions in DB
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE roles SET permissions = '{}'::jsonb WHERE name = ?"
             )) {
            ps.setString(1, "mutable");
            ps.executeUpdate();
        }
        // Invalidate
        this.policy.invalidate("mutable");
        this.policy.invalidate("eve");
        // Should reflect new state
        assertFalse(
            this.policy.getPermissions(new AuthUser("eve", "local"))
                .implies(new ApiSearchPermission(ApiSearchPermission.SearchAction.READ)),
            "After invalidation, removed permissions should no longer be granted"
        );
    }

    @Test
    void handlesUserNotInDb() {
        final UserPermissions perms = this.policy.getPermissions(
            new AuthUser("unknown_user", "local")
        );
        assertFalse(
            perms.implies(new ApiSearchPermission(ApiSearchPermission.SearchAction.READ)),
            "Unknown user should have no permissions"
        );
    }

    @Test
    void handlesMultipleRoles() throws Exception {
        createRole("searcher", Json.createObjectBuilder()
            .add("permissions", Json.createObjectBuilder()
                .add("api_search_permissions", Json.createArrayBuilder().add("read"))
            ).build().toString()
        );
        createRole("go_dev", Json.createObjectBuilder()
            .add("permissions", Json.createObjectBuilder()
                .add("adapter_basic_permissions", Json.createObjectBuilder()
                    .add("go-repo", Json.createArrayBuilder().add("read").add("write")))
            ).build().toString()
        );
        createUser("frank", true);
        assignRole("frank", "searcher");
        assignRole("frank", "go_dev");
        final UserPermissions perms = this.policy.getPermissions(
            new AuthUser("frank", "local")
        );
        assertTrue(
            perms.implies(new ApiSearchPermission(ApiSearchPermission.SearchAction.READ)),
            "User should have search permission from searcher role"
        );
        assertTrue(
            perms.implies(new AdapterBasicPermission("go-repo", "write")),
            "User should have write permission from go_dev role"
        );
    }

    private void createRole(final String name, final String permsJson) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO roles (name, permissions) VALUES (?, ?::jsonb)"
             )) {
            ps.setString(1, name);
            ps.setString(2, permsJson);
            ps.executeUpdate();
        }
    }

    private void disableRole(final String name) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE roles SET enabled = false WHERE name = ?"
             )) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    private void createUser(final String name, final boolean enabled) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (username, password_hash, enabled) VALUES (?, 'test', ?)"
             )) {
            ps.setString(1, name);
            ps.setBoolean(2, enabled);
            ps.executeUpdate();
        }
    }

    private void assignRole(final String username, final String roleName) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO user_roles (user_id, role_id) "
                 + "SELECT u.id, r.id FROM users u, roles r "
                 + "WHERE u.username = ? AND r.name = ?"
             )) {
            ps.setString(1, username);
            ps.setString(2, roleName);
            ps.executeUpdate();
        }
    }
}
