/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.security.policy;

import com.artipie.ArtipieException;
import com.artipie.asto.misc.Cleanable;
import com.artipie.asto.misc.UncheckedFunc;
import com.artipie.asto.misc.UncheckedSupplier;
import com.artipie.http.auth.AuthUser;
import com.artipie.http.log.EcsLogger;
import com.artipie.security.perms.EmptyPermissions;
import com.artipie.security.perms.PermissionConfig;
import com.artipie.security.perms.PermissionsLoader;
import com.artipie.security.perms.User;
import com.artipie.security.perms.UserPermissions;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.sql.DataSource;

/**
 * Database-backed policy implementation. Reads user roles and role permissions
 * from PostgreSQL and uses Caffeine cache to avoid hitting the database on
 * every request.
 * <p>
 * Drop-in replacement for {@link CachedYamlPolicy} when a database is
 * configured. Users get permissions exclusively through roles (no individual
 * user-level permissions in the DB model).
 *
 * @since 1.21
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class CachedDbPolicy implements Policy<UserPermissions>, Cleanable<String> {

    /**
     * Permissions factories.
     */
    private static final PermissionsLoader FACTORIES = new PermissionsLoader();

    /**
     * Cache for usernames and {@link UserPermissions}.
     */
    private final Cache<String, UserPermissions> cache;

    /**
     * Cache for usernames and user with roles.
     */
    private final Cache<String, User> users;

    /**
     * Cache for role name and role permissions.
     */
    private final Cache<String, PermissionCollection> roles;

    /**
     * Database data source.
     */
    private final DataSource source;

    /**
     * Ctor with default cache settings.
     * @param source Database data source
     */
    public CachedDbPolicy(final DataSource source) {
        this(
            Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(3))
                .recordStats()
                .build(),
            Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(3))
                .recordStats()
                .build(),
            Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterAccess(Duration.ofMinutes(3))
                .recordStats()
                .build(),
            source
        );
    }

    /**
     * Primary ctor.
     * @param cache Cache for usernames and {@link UserPermissions}
     * @param users Cache for username and user roles
     * @param roles Cache for role name and role permissions
     * @param source Database data source
     */
    public CachedDbPolicy(
        final Cache<String, UserPermissions> cache,
        final Cache<String, User> users,
        final Cache<String, PermissionCollection> roles,
        final DataSource source
    ) {
        this.cache = cache;
        this.users = users;
        this.roles = roles;
        this.source = source;
    }

    @Override
    public UserPermissions getPermissions(final AuthUser user) {
        return this.cache.get(user.name(), key -> {
            try {
                return this.createUserPermissions(user).call();
            } catch (final Exception err) {
                EcsLogger.error("com.artipie.security")
                    .message("Failed to get user permissions from DB")
                    .eventCategory("security")
                    .eventAction("permissions_get")
                    .eventOutcome("failure")
                    .field("user.name", user.name())
                    .error(err)
                    .log();
                throw new ArtipieException(err);
            }
        });
    }

    @Override
    public void invalidate(final String key) {
        if (this.cache.getIfPresent(key) != null || this.users.getIfPresent(key) != null) {
            this.cache.invalidate(key);
            this.users.invalidate(key);
        } else {
            this.roles.invalidate(key);
        }
    }

    @Override
    public void invalidateAll() {
        this.cache.invalidateAll();
        this.users.invalidateAll();
        this.roles.invalidateAll();
    }

    /**
     * Create {@link UserPermissions} callable for cache loading.
     * @param user Authenticated user
     * @return Callable that creates UserPermissions
     */
    private Callable<UserPermissions> createUserPermissions(final AuthUser user) {
        return () -> new UserPermissions(
            new UncheckedSupplier<>(
                () -> this.users.get(user.name(), key -> new DbUser(this.source, user))
            ),
            new UncheckedFunc<>(
                role -> this.roles.get(
                    role, key -> CachedDbPolicy.rolePermissions(this.source, key)
                )
            )
        );
    }

    /**
     * Load role permissions from database.
     * @param ds Data source
     * @param role Role name
     * @return Permissions of the role
     */
    static PermissionCollection rolePermissions(final DataSource ds, final String role) {
        final String sql = "SELECT permissions, enabled FROM roles WHERE name = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            final ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return EmptyPermissions.INSTANCE;
            }
            if (!rs.getBoolean("enabled")) {
                return EmptyPermissions.INSTANCE;
            }
            final String permsJson = rs.getString("permissions");
            if (permsJson == null || permsJson.isEmpty()) {
                return EmptyPermissions.INSTANCE;
            }
            return readPermissionsFromJson(
                Json.createReader(new StringReader(permsJson)).readObject()
            );
        } catch (final Exception ex) {
            EcsLogger.error("com.artipie.security")
                .message("Failed to read role permissions from DB")
                .eventCategory("security")
                .eventAction("role_permissions_read")
                .eventOutcome("failure")
                .field("user.roles", role)
                .error(ex)
                .log();
            return EmptyPermissions.INSTANCE;
        }
    }

    /**
     * Parse permissions from the JSON stored in the DB permissions column.
     * The DB stores the full API body, e.g.:
     * {@code {"permissions": {"api_search_permissions": ["read"], ...}}}
     * @param stored The JSON object from the permissions column
     * @return Permission collection
     */
    private static PermissionCollection readPermissionsFromJson(final JsonObject stored) {
        final JsonObject all;
        if (stored.containsKey("permissions")) {
            all = stored.getJsonObject("permissions");
        } else {
            all = stored;
        }
        if (all == null || all.isEmpty()) {
            return EmptyPermissions.INSTANCE;
        }
        final Permissions res = new Permissions();
        for (final String type : all.keySet()) {
            final JsonValue perms = all.get(type);
            final PermissionConfig config;
            if (perms instanceof JsonObject) {
                config = new PermissionConfig.FromJsonObject((JsonObject) perms);
            } else if (perms instanceof javax.json.JsonArray) {
                config = new PermissionConfig.FromJsonArray((javax.json.JsonArray) perms);
            } else {
                config = new PermissionConfig.FromJsonObject(
                    Json.createObjectBuilder().build()
                );
            }
            Collections.list(FACTORIES.newObject(type, config).elements())
                .forEach(res::add);
        }
        return res;
    }

    /**
     * User loaded from database.
     * @since 1.21
     */
    static final class DbUser implements User {

        /**
         * User individual permissions (always empty for DB users).
         */
        private final PermissionCollection perms;

        /**
         * User roles.
         */
        private final Collection<String> uroles;

        /**
         * Ctor.
         * @param ds Data source
         * @param user Authenticated user
         */
        DbUser(final DataSource ds, final AuthUser user) {
            final UserRecord rec = DbUser.loadFromDb(ds, user.name());
            if (rec.disabled) {
                this.perms = EmptyPermissions.INSTANCE;
                this.uroles = Collections.emptyList();
            } else {
                this.perms = EmptyPermissions.INSTANCE;
                final List<String> rlist = new ArrayList<>(rec.roles);
                if (user.authContext() != null && !user.authContext().isEmpty()) {
                    rlist.add(String.format("default/%s", user.authContext()));
                }
                this.uroles = rlist;
            }
        }

        @Override
        public PermissionCollection perms() {
            return this.perms;
        }

        @Override
        public Collection<String> roles() {
            return this.uroles;
        }

        /**
         * Load user record from database.
         * @param ds Data source
         * @param username Username
         * @return User record with enabled status and role names
         */
        private static UserRecord loadFromDb(final DataSource ds, final String username) {
            final String sql = String.join(" ",
                "SELECT u.enabled,",
                "COALESCE(json_agg(r.name) FILTER (WHERE r.name IS NOT NULL), '[]') AS roles",
                "FROM users u",
                "LEFT JOIN user_roles ur ON u.id = ur.user_id",
                "LEFT JOIN roles r ON ur.role_id = r.id",
                "WHERE u.username = ?",
                "GROUP BY u.id"
            );
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                final ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    EcsLogger.warn("com.artipie.security")
                        .message("User not found in DB for policy lookup")
                        .eventCategory("security")
                        .eventAction("user_lookup")
                        .eventOutcome("failure")
                        .field("user.name", username)
                        .log();
                    return new UserRecord(true, Collections.emptyList());
                }
                final boolean enabled = rs.getBoolean("enabled");
                final List<String> roles = new ArrayList<>();
                final String rolesJson = rs.getString("roles");
                if (rolesJson != null) {
                    final javax.json.JsonArray arr = Json.createReader(
                        new StringReader(rolesJson)
                    ).readArray();
                    for (int i = 0; i < arr.size(); i++) {
                        roles.add(arr.getString(i));
                    }
                }
                return new UserRecord(!enabled, roles);
            } catch (final Exception ex) {
                EcsLogger.error("com.artipie.security")
                    .message("Failed to load user from DB for policy")
                    .eventCategory("security")
                    .eventAction("user_lookup")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .error(ex)
                    .log();
                return new UserRecord(true, Collections.emptyList());
            }
        }

        /**
         * Simple record for user data from DB query.
         * @since 1.21
         */
        private static final class UserRecord {
            /**
             * Whether user is disabled.
             */
            final boolean disabled;

            /**
             * User's role names.
             */
            final List<String> roles;

            /**
             * Ctor.
             * @param disabled Whether user is disabled
             * @param roles User's role names
             */
            UserRecord(final boolean disabled, final List<String> roles) {
                this.disabled = disabled;
                this.roles = roles;
            }
        }
    }
}
