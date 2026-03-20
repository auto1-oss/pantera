/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.security.policy;

import com.amihaiemil.eoyaml.Node;
import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.asto.misc.UncheckedFunc;
import com.auto1.pantera.asto.misc.UncheckedSupplier;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.perms.EmptyPermissions;
import com.auto1.pantera.security.perms.PermissionConfig;
import com.auto1.pantera.security.perms.PermissionsLoader;
import com.auto1.pantera.security.perms.User;
import com.auto1.pantera.security.perms.UserPermissions;
import com.auto1.pantera.cache.CacheConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.auto1.pantera.http.log.EcsLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.time.Duration;

/**
 * Cached yaml policy implementation obtains permissions from yaml files and uses
 * Caffeine cache to avoid reading yamls from storage on each request.
 * 
 * <p>Configuration in _server.yaml:
 * <pre>
 * caches:
 *   policy-perms:
 *     profile: default  # Or direct: maxSize: 10000, ttl: 24h
 *   policy-users:
 *     profile: default
 *   policy-roles:
 *     maxSize: 1000
 *     ttl: 5m
 * </pre>
 * <p/>
 * The storage itself is expected to have yaml files with permissions in the following structure:
 * <pre>
 * ..
 * ├── roles
 * │   ├── java-dev.yaml
 * │   ├── admin.yaml
 * │   ├── ...
 * ├── users
 * │   ├── david.yaml
 * │   ├── jane.yaml
 * │   ├── ...
 * </pre>
 * Roles yaml file name is the name of the role, format example for `java-dev.yaml`:
 * <pre>{@code
 * permissions:
 *   adapter_basic_permissions:
 *     maven-repo:
 *       - read
 *       - write
 *     python-repo:
 *       - read
 *     npm-repo:
 *       - read
 * }</pre>
 * Or for `admin.yaml`:
 * <pre>{@code
 * enabled: true # optional default true
 * permissions:
 *   all_permission: {}
 * }</pre>
 * Role can be disabled with the help of optional {@code enabled} field.
 * <p>User yaml format example, file name is the name of the user:
 * <pre>{@code
 * type: plain
 * pass: qwerty
 * email: david@example.com # Optional
 * enabled: true # optional default true
 * roles:
 *   - java-dev
 * permissions:
 *   pantera_basic_permission:
 *     rpm-repo:
 *       - read
 * }</pre>
 * @since 1.2
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class CachedYamlPolicy implements Policy<UserPermissions>, Cleanable<String> {

    /**
     * Permissions factories.
     */
    private static final PermissionsLoader FACTORIES = new PermissionsLoader();

    /**
     * Empty permissions' config.
     */
    private static final PermissionConfig EMPTY_CONFIG =
        new PermissionConfig.FromYamlMapping(Yaml.createYamlMappingBuilder().build());

    /**
     * Cache for usernames and {@link UserPermissions}.
     */
    private final Cache<String, UserPermissions> cache;

    /**
     * Cache for usernames and user with his roles and individual permissions.
     */
    private final Cache<String, User> users;

    /**
     * Cache for role name and role permissions.
     */
    private final Cache<String, PermissionCollection> roles;

    /**
     * Storage to read users and roles yaml files from.
     */
    private final BlockingStorage asto;

    /**
     * Primary ctor.
     * @param cache Cache for usernames and {@link UserPermissions}
     * @param users Cache for username and user individual permissions
     * @param roles Cache for role name and role permissions
     * @param asto Storage to read users and roles yaml files from
     */
    public CachedYamlPolicy(
        final Cache<String, UserPermissions> cache,
        final Cache<String, User> users,
        final Cache<String, PermissionCollection> roles,
        final BlockingStorage asto
    ) {
        this.cache = cache;
        this.users = users;
        this.roles = roles;
        this.asto = asto;
    }

    /**
     * Ctor with legacy eviction time (for backward compatibility).
     * @param asto Storage to read users and roles yaml files from
     * @param eviction Eviction time in milliseconds
     */
    public CachedYamlPolicy(final BlockingStorage asto, final long eviction) {
        this(
            Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMillis(eviction))
                .recordStats()
                .build(),
            Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMillis(eviction))
                .recordStats()
                .build(),
            Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterAccess(Duration.ofMillis(eviction))
                .recordStats()
                .build(),
            asto
        );
    }
    
    /**
     * Ctor with configuration support.
     * @param asto Storage to read users and roles yaml files from
     * @param serverYaml Server configuration YAML
     */
    public CachedYamlPolicy(final BlockingStorage asto, final YamlMapping serverYaml) {
        this(
            createCache(CacheConfig.from(serverYaml, "policy-perms")),
            createCache(CacheConfig.from(serverYaml, "policy-users")),
            createCache(CacheConfig.from(serverYaml, "policy-roles")),
            asto
        );
    }
    
    /**
     * Create Caffeine cache from configuration.
     * @param config Cache configuration
     * @param <K> Key type
     * @param <V> Value type
     * @return Configured cache
     */
    private static <K, V> Cache<K, V> createCache(final CacheConfig config) {
        return Caffeine.newBuilder()
            .maximumSize(config.maxSize())
            .expireAfterAccess(config.ttl())
            .recordStats()
            .build();
    }

    @Override
    public UserPermissions getPermissions(final AuthUser user) {
        return this.cache.get(user.name(), key -> {
            try {
                return this.createUserPermissions(user).call();
            } catch (Exception err) {
                EcsLogger.error("com.auto1.pantera.security")
                    .message("Failed to get user permissions")
                    .eventCategory("security")
                    .eventAction("permissions_get")
                    .eventOutcome("failure")
                    .field("user.name", user.name())
                    .error(err)
                    .log();
                throw new PanteraException(err);
            }
        });
    }

    @Override
    public void invalidate(final String key) {
        // Check if it's a user or role and invalidate accordingly
        if (this.cache.getIfPresent(key) != null || this.users.getIfPresent(key) != null) {
            this.cache.invalidate(key);
            this.users.invalidate(key);
        } else {
            // Assume it's a role
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
     * Get role permissions.
     * @param asto Storage to read the role permissions from
     * @param role Role name
     * @return Permissions of the role
     */
    static PermissionCollection rolePermissions(final BlockingStorage asto, final String role) {
        PermissionCollection res;
        final String filename = String.format("roles/%s", role);
        try {
            final YamlMapping mapping = CachedYamlPolicy.readFile(asto, filename);
            final String enabled = mapping.string(AstoUser.ENABLED);
            if (Boolean.FALSE.toString().equalsIgnoreCase(enabled)) {
                res = EmptyPermissions.INSTANCE;
            } else {
                res = CachedYamlPolicy.readPermissionsFromYaml(mapping);
            }
        } catch (final IOException | ValueNotFoundException err) {
            EcsLogger.error("com.auto1.pantera.security")
                .message("Failed to read/parse role permissions file")
                .eventCategory("security")
                .eventAction("role_permissions_read")
                .eventOutcome("failure")
                .field("file.name", filename)
                .field("user.roles", role)
                .log();
            res = EmptyPermissions.INSTANCE;
        }
        return res;
    }

    /**
     * Create instance for {@link UserPermissions} if not found in cache,
     * arguments for the {@link UserPermissions} ctor are the following:
     * 1) supplier for user individual permissions and roles
     * 2) function to get permissions of the role.
     * @param user Username
     * @return Callable to create {@link UserPermissions}
     */
    private Callable<UserPermissions> createUserPermissions(final AuthUser user) {
        return () -> new UserPermissions(
            new UncheckedSupplier<>(
                () -> this.users.get(user.name(), key -> new AstoUser(this.asto, user))
            ),
            new UncheckedFunc<>(
                role -> this.roles.get(
                    role, key -> CachedYamlPolicy.rolePermissions(this.asto, key)
                )
            )
        );
    }

    /**
     * Read yaml file from storage considering both yaml and yml extensions. If nighter
     * version exists, exception is thrown.
     * @param asto Blocking storage
     * @param filename The name of the file
     * @return The value in bytes
     * @throws ValueNotFoundException If file not found
     * @throws IOException If yaml parsing failed
     */
    private static YamlMapping readFile(final BlockingStorage asto, final String filename)
        throws IOException {
        final byte[] res;
        final Key yaml = new Key.From(String.format("%s.yaml", filename));
        final Key yml = new Key.From(String.format("%s.yml", filename));
        if (asto.exists(yaml)) {
            res = asto.value(yaml);
        } else if (asto.exists(yml)) {
            res = asto.value(yml);
        } else {
            throw new ValueNotFoundException(yaml);
        }
        return Yaml.createYamlInput(new ByteArrayInputStream(res)).readYamlMapping();
    }

    /**
     * Read and instantiate permissions from yaml mapping.
     * @param mapping Yaml mapping
     * @return Permissions set
     */
    private static PermissionCollection readPermissionsFromYaml(final YamlMapping mapping) {
        final YamlMapping all = mapping.yamlMapping("permissions");
        final PermissionCollection res;
        if (all == null || all.keys().isEmpty()) {
            res = EmptyPermissions.INSTANCE;
        } else {
            res = new Permissions();
            for (final String type : all.keys().stream().map(item -> item.asScalar().value())
                .collect(Collectors.toSet())) {
                final YamlNode perms = all.value(type);
                final PermissionConfig config;
                if (perms != null && perms.type() == Node.MAPPING) {
                    config = new PermissionConfig.FromYamlMapping(perms.asMapping());
                } else if (perms != null && perms.type() == Node.SEQUENCE) {
                    config = new PermissionConfig.FromYamlSequence(perms.asSequence());
                } else {
                    config = CachedYamlPolicy.EMPTY_CONFIG;
                }
                Collections.list(FACTORIES.newObject(type, config).elements()).forEach(res::add);
            }
        }
        return res;
    }

    /**
     * User from storage.
     * @since 1.2
     */
    @SuppressWarnings({
        "PMD.AvoidFieldNameMatchingMethodName",
        "PMD.ConstructorOnlyInitializesOrCallOtherConstructors"
    })
    public static final class AstoUser implements User {

        /**
         * String to format user settings file name.
         */
        private static final String ENABLED = "enabled";

        /**
         * String to format user settings file name.
         */
        private static final String FORMAT = "users/%s";

        /**
         * User individual permission.
         */
        private final PermissionCollection perms;

        /**
         * User roles.
         */
        private final Collection<String> roles;

        /**
         * Ctor.
         * @param asto Storage to read user yaml file from
         * @param user The name of the user
         */
        AstoUser(final BlockingStorage asto, final AuthUser user) {
            final YamlMapping yaml = getYamlMapping(asto, user.name());
            this.perms = perms(yaml);
            this.roles = roles(yaml, user);
        }

        @Override
        public PermissionCollection perms() {
            return this.perms;
        }

        @Override
        public Collection<String> roles() {
            return this.roles;
        }

        /**
         * Get supplier to read user permissions from storage.
         * @param yaml Yaml to read permissions from
         * @return User permissions supplier
         */
        private static PermissionCollection perms(final YamlMapping yaml) {
            final PermissionCollection res;
            if (AstoUser.disabled(yaml)) {
                res = EmptyPermissions.INSTANCE;
            } else {
                res = CachedYamlPolicy.readPermissionsFromYaml(yaml);
            }
            return res;
        }

        /**
         * Get user roles collection.
         * @param yaml Yaml to read roles from
         * @param user Authenticated user
         * @return Roles collection
         */
        private static Collection<String> roles(final YamlMapping yaml, final AuthUser user) {
            Set<String> roles = Collections.emptySet();
            if (!AstoUser.disabled(yaml)) {
                final YamlSequence sequence = yaml.yamlSequence("roles");
                if (sequence != null) {
                    roles = sequence.values().stream().map(item -> item.asScalar().value())
                        .collect(Collectors.toSet());
                }
                if (user.authContext() != null && !user.authContext().isEmpty()) {
                    final String role = String.format("default/%s", user.authContext());
                    if (roles.isEmpty()) {
                        roles = Collections.singleton(role);
                    } else {
                        roles.add(role);
                    }
                }
            }
            return roles;
        }

        /**
         * Is user enabled?
         * @param yaml Yaml to check disabled item from
         * @return True is user is active
         */
        private static boolean disabled(final YamlMapping yaml) {
            return Boolean.FALSE.toString().equalsIgnoreCase(yaml.string(AstoUser.ENABLED));
        }

        /**
         * Read yaml mapping properly handling the possible errors.
         * @param asto Storage to read user yaml file from
         * @param username The name of the user
         * @return Yaml mapping
         */
        private static YamlMapping getYamlMapping(final BlockingStorage asto,
            final String username) {
            final String filename = String.format(AstoUser.FORMAT, username);
            YamlMapping res;
            try {
                res = CachedYamlPolicy.readFile(asto, filename);
            } catch (final IOException | ValueNotFoundException err) {
                EcsLogger.error("com.auto1.pantera.security")
                    .message("Failed to read or parse user file")
                    .eventCategory("security")
                    .eventAction("user_file_read")
                    .eventOutcome("failure")
                    .field("file.name", filename)
                    .field("user.name", username)
                    .log();
                res = Yaml.createYamlMappingBuilder().build();
            }
            return res;
        }
    }
}
