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
package com.auto1.pantera.db.migration;

import com.amihaiemil.eoyaml.Node;
import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.db.dao.AuthProviderDao;
import com.auto1.pantera.db.dao.RoleDao;
import com.auto1.pantera.db.dao.RepositoryDao;
import com.auto1.pantera.db.dao.SettingsDao;
import com.auto1.pantera.db.dao.StorageAliasDao;
import com.auto1.pantera.db.dao.UserDao;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.sql.DataSource;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time migration from YAML config files to PostgreSQL.
 * Checks for {@code migration_completed} flag in settings table.
 * If absent, reads YAML files and populates DB tables.
 * If present, skips entirely.
 * @since 1.0
 */
public final class YamlToDbMigrator {

    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(YamlToDbMigrator.class);

    /**
     * Settings key for migration flag.
     */
    private static final String MIGRATION_KEY = "migration_completed";

    /**
     * Current migration version. Bump this when new migration logic
     * is added. The migration re-runs (idempotently) when the stored
     * version is lower than this value.
     *
     * <p>v5: re-import auth providers so existing installs pick up the
     * new {@code allowed-groups} field and any other fields admins
     * added to pantera.yml since initial provisioning. The re-import
     * is a DO NOTHING on conflict in AuthProviderDao for name fields
     * but replaces the {@code config} JSONB entirely.
     *
     * <p>v6: bootstrap default {@code local} + {@code jwt-password}
     * providers and (on empty users table) the default
     * {@code admin/admin} user with {@code must_change_password = true}.
     */
    private static final int MIGRATION_VERSION = 6;

    /**
     * DataSource for DB access.
     */
    private final DataSource source;

    /**
     * Path to security directory (contains roles/, users/).
     */
    private final Path securityDir;

    /**
     * Path to the repos directory (contains *.yaml repo configs).
     */
    private final Path reposDir;

    /**
     * Path to the pantera.yml config file.
     */
    private final Path panteraYml;

    /**
     * Ctor.
     * @param source DataSource for DB
     * @param securityDir Path to the security directory (contains roles/, users/)
     * @param reposDir Path to the repos directory
     */
    public YamlToDbMigrator(final DataSource source, final Path securityDir,
        final Path reposDir) {
        this(source, securityDir, reposDir, null);
    }

    /**
     * Full ctor.
     * @param source DataSource for DB
     * @param securityDir Path to the security directory (contains roles/, users/)
     * @param reposDir Path to the repos directory
     * @param panteraYml Path to pantera.yml (null to skip settings migration)
     */
    public YamlToDbMigrator(final DataSource source, final Path securityDir,
        final Path reposDir, final Path panteraYml) {
        this.source = source;
        this.securityDir = securityDir;
        this.reposDir = reposDir;
        this.panteraYml = panteraYml;
    }

    /**
     * Run migration. Uses a single versioned flag: if the stored version
     * is lower than {@link #MIGRATION_VERSION}, the full migration re-runs
     * idempotently (all DAOs use upsert).
     * @return True if migration was executed, false if skipped
     */
    public boolean migrate() {
        final SettingsDao settings = new SettingsDao(this.source);
        final int storedVersion = settings.get(YamlToDbMigrator.MIGRATION_KEY)
            .map(obj -> obj.getInt("version", 0))
            .orElse(0);
        if (storedVersion >= YamlToDbMigrator.MIGRATION_VERSION) {
            LOG.info("YAML-to-DB migration v{} already completed, skipping",
                storedVersion);
            return false;
        }
        LOG.info("Running YAML-to-DB migration (v{} -> v{})...",
            storedVersion, YamlToDbMigrator.MIGRATION_VERSION);
        this.migrateRepos();
        final Path rolesDir = this.securityDir.resolve("roles");
        if (Files.isDirectory(rolesDir)) {
            this.migrateRoles(rolesDir);
        }
        final Path usersDir = this.securityDir.resolve("users");
        if (Files.isDirectory(usersDir)) {
            this.migrateUsers(usersDir);
        }
        this.migratePanteraYml();
        settings.put(
            YamlToDbMigrator.MIGRATION_KEY,
            Json.createObjectBuilder()
                .add("completed", true)
                .add("version", YamlToDbMigrator.MIGRATION_VERSION)
                .add("timestamp", System.currentTimeMillis())
                .build(),
            "system"
        );
        LOG.info("YAML-to-DB migration v{} completed", YamlToDbMigrator.MIGRATION_VERSION);
        return true;
    }

    /**
     * Migrate repository YAML configs from reposDir.
     */
    private void migrateRepos() {
        if (!Files.isDirectory(this.reposDir)) {
            LOG.info("No repos directory at {}, skipping", this.reposDir);
            return;
        }
        final RepositoryDao dao = new RepositoryDao(this.source);
        try (DirectoryStream<Path> stream =
            Files.newDirectoryStream(this.reposDir, "*.{yaml,yml}")) {
            for (final Path file : stream) {
                try {
                    final String name = file.getFileName().toString()
                        .replaceAll("\\.(yaml|yml)$", "");
                    if (name.startsWith("_")) {
                        continue;
                    }
                    final YamlMapping yaml = Yaml.createYamlInput(
                        Files.readString(file)
                    ).readYamlMapping();
                    dao.save(
                        new RepositoryName.Simple(name),
                        yamlToJson(yaml),
                        "migration"
                    );
                    LOG.info("Migrated repository: {}", name);
                } catch (final Exception ex) {
                    LOG.error("Failed to migrate repo file: {}", file, ex);
                }
            }
        } catch (final IOException ex) {
            LOG.error("Failed to read repos directory: {}", this.reposDir, ex);
        }
    }

    /**
     * Migrate user YAML files.
     * @param usersDir Path to security/users directory
     */
    private void migrateUsers(final Path usersDir) {
        final UserDao dao = new UserDao(this.source);
        try (DirectoryStream<Path> stream =
            Files.newDirectoryStream(usersDir, "*.{yaml,yml}")) {
            for (final Path file : stream) {
                try {
                    final String name = file.getFileName().toString()
                        .replaceAll("\\.(yaml|yml)$", "");
                    final YamlMapping yaml = Yaml.createYamlInput(
                        Files.readString(file)
                    ).readYamlMapping();
                    final JsonObjectBuilder builder = Json.createObjectBuilder();
                    builder.add("name", name);
                    // Hash password with bcrypt if type is "plain"
                    final String pass = yaml.string("pass");
                    final String credType = yaml.string("type");
                    if (pass != null) {
                        if ("plain".equals(credType)) {
                            builder.add("pass", BCrypt.hashpw(pass, BCrypt.gensalt()));
                        } else {
                            builder.add("pass", pass);
                        }
                    }
                    // Preserve the original auth type from YAML.
                    // "plain" and "sha256" are password formats → map to "local".
                    // Actual provider names (okta, keycloak) are preserved.
                    if (credType != null
                        && !"plain".equals(credType) && !"sha256".equals(credType)) {
                        builder.add("type", credType);
                    } else {
                        builder.add("type", "local");
                    }
                    if (yaml.string("email") != null) {
                        builder.add("email", yaml.string("email"));
                    }
                    final String enabled = yaml.string("enabled");
                    builder.add(
                        "enabled",
                        enabled == null || Boolean.parseBoolean(enabled)
                    );
                    // Migrate role assignments
                    final YamlSequence rolesSeq = yaml.yamlSequence("roles");
                    if (rolesSeq != null) {
                        final JsonArrayBuilder rolesArr = Json.createArrayBuilder();
                        for (final YamlNode node : rolesSeq) {
                            rolesArr.add(node.asScalar().value());
                        }
                        builder.add("roles", rolesArr);
                    }
                    dao.addOrUpdate(builder.build(), name);
                    LOG.info("Migrated user: {}", name);
                } catch (final Exception ex) {
                    LOG.error("Failed to migrate user file: {}", file, ex);
                }
            }
        } catch (final IOException ex) {
            LOG.error("Failed to read users directory: {}", usersDir, ex);
        }
    }

    /**
     * Migrate role YAML files, including subdirectories like {@code default/}.
     * Subdirectory roles are stored with names like {@code default/github}
     * to match the convention used by {@code CachedDbPolicy.DbUser}.
     * @param rolesDir Path to security/roles directory
     */
    private void migrateRoles(final Path rolesDir) {
        final RoleDao dao = new RoleDao(this.source);
        this.migrateRoleFiles(dao, rolesDir, "");
    }

    /**
     * Recursively migrate role YAML files from a directory.
     * @param dao Role DAO
     * @param dir Directory to scan
     * @param prefix Name prefix (e.g. "" for top-level, "default/" for subdirs)
     */
    private void migrateRoleFiles(final RoleDao dao, final Path dir, final String prefix) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (final Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    this.migrateRoleFiles(
                        dao, entry,
                        prefix + entry.getFileName().toString() + "/"
                    );
                } else if (entry.toString().matches(".*\\.(yaml|yml)$")) {
                    try {
                        final String name = prefix + entry.getFileName().toString()
                            .replaceAll("\\.(yaml|yml)$", "");
                        final YamlMapping yaml = Yaml.createYamlInput(
                            Files.readString(entry)
                        ).readYamlMapping();
                        dao.addOrUpdate(yamlToJson(yaml), name);
                        LOG.info("Migrated role: {}", name);
                    } catch (final Exception ex) {
                        LOG.error("Failed to migrate role file: {}", entry, ex);
                    }
                }
            }
        } catch (final IOException ex) {
            LOG.error("Failed to read roles directory: {}", dir, ex);
        }
    }

    /**
     * Migrate pantera.yml meta section to settings + auth_providers tables.
     * Imports ALL configuration sections: simple keys, jwt, cooldown,
     * http_client, http_server, metrics, caches, global_prefixes,
     * storage aliases, and auth providers.
     */
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.NPathComplexity"})
    private void migratePanteraYml() {
        if (this.panteraYml == null || !Files.isRegularFile(this.panteraYml)) {
            LOG.info("No pantera.yml path provided or file not found, skipping settings migration");
            return;
        }
        try {
            final YamlMapping yaml = Yaml.createYamlInput(
                Files.readString(this.panteraYml)
            ).readYamlMapping();
            final YamlMapping meta = yaml.yamlMapping("meta");
            if (meta == null) {
                return;
            }
            final SettingsDao settings = new SettingsDao(this.source);
            // Migrate simple key-value settings
            for (final String key : new String[]{"layout", "port", "base_path"}) {
                final String val = meta.string(key);
                if (val != null) {
                    settings.put(
                        key,
                        Json.createObjectBuilder()
                            .add("value", resolveEnvVars(val)).build(),
                        "migration"
                    );
                }
            }
            // Migrate all nested settings sections as JSONB
            for (final String section : new String[]{
                "jwt", "cooldown", "http_client", "http_server", "metrics", "caches"
            }) {
                final YamlMapping nested = meta.yamlMapping(section);
                if (nested != null) {
                    settings.put(section, yamlToJson(nested), "migration");
                    LOG.info("Migrated settings section: {}", section);
                }
            }
            // Migrate global_prefixes as a JSON array
            final YamlSequence prefixes = meta.yamlSequence("global_prefixes");
            if (prefixes != null) {
                final JsonArrayBuilder arr = Json.createArrayBuilder();
                for (final YamlNode node : prefixes) {
                    arr.add(resolveEnvVars(node.asScalar().value()));
                }
                settings.put(
                    "global_prefixes",
                    Json.createObjectBuilder().add("prefixes", arr).build(),
                    "migration"
                );
                LOG.info("Migrated global_prefixes");
            }
            // Migrate global storage aliases from meta.storage
            final YamlMapping storage = meta.yamlMapping("storage");
            if (storage != null) {
                final StorageAliasDao aliasDao = new StorageAliasDao(this.source);
                aliasDao.put("default", null, yamlToJson(storage));
                LOG.info("Migrated default storage alias");
            }
            // Migrate named storage aliases from meta.storages
            final YamlMapping storages = meta.yamlMapping("storages");
            if (storages != null) {
                final StorageAliasDao aliasDao = new StorageAliasDao(this.source);
                for (final YamlNode key : storages.keys()) {
                    final String aliasName = key.asScalar().value();
                    final YamlMapping aliasConfig = storages.yamlMapping(key);
                    if (aliasConfig != null) {
                        aliasDao.put(aliasName, null, yamlToJson(aliasConfig));
                        LOG.info("Migrated storage alias: {}", aliasName);
                    }
                }
            }
            // Migrate auth providers (credentials list).
            // Always seed the default `local` and `jwt-password` providers
            // so a fresh install has both username/password and API token
            // auth available out of the box. ensureExists is a no-op if a
            // row already exists, preserving admin edits across restarts.
            final AuthProviderDao authDao = new AuthProviderDao(this.source);
            if (authDao.ensureExists("local", 0, Json.createObjectBuilder().build())) {
                LOG.info("Bootstrapped default 'local' auth provider");
            }
            if (authDao.ensureExists("jwt-password", 1, Json.createObjectBuilder().build())) {
                LOG.info("Bootstrapped default 'jwt-password' auth provider");
            }
            final YamlSequence creds = meta.yamlSequence("credentials");
            if (creds != null) {
                int priority = 1;
                for (final YamlNode node : creds) {
                    final YamlMapping provider = node.asMapping();
                    final String type = provider.string("type");
                    if (type != null) {
                        authDao.put(type, priority, yamlToJson(provider));
                        priority++;
                    }
                }
                LOG.info("Migrated {} auth providers", priority - 1);
            }
            // Bootstrap default admin user + role on a fresh install (idempotent).
            bootstrapDefaultAdmin();
            LOG.info("Migrated pantera.yml settings (all sections)");
        } catch (final Exception ex) {
            LOG.error("Failed to migrate pantera.yml: {}", this.panteraYml, ex);
        }
    }

    /**
     * Bootstrap a default {@code admin/admin} user with the {@code admin}
     * role on a fresh install. Idempotent — only runs if the {@code users}
     * table is empty. The admin user is created with
     * {@code must_change_password = true}, forcing a password change on
     * first login (Pantera blocks all other API calls until that's done).
     */
    private void bootstrapDefaultAdmin() {
        try (Connection conn = this.source.getConnection()) {
            // 1. Bail out if any user already exists.
            try (PreparedStatement count = conn.prepareStatement(
                "SELECT COUNT(*) FROM users")) {
                try (ResultSet rs = count.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return;
                    }
                }
            }
            // 2. Ensure the admin role exists with all_permission. Use INSERT
            //    ON CONFLICT DO NOTHING so we don't overwrite a manually
            //    created admin role with different perms.
            try (PreparedStatement role = conn.prepareStatement(String.join(" ",
                "INSERT INTO roles (name, permissions) VALUES",
                "('admin', '{\"permissions\": {\"all_permission\": {}}}'::jsonb)",
                "ON CONFLICT (name) DO NOTHING"
            ))) {
                role.executeUpdate();
            }
            // 3. Insert the admin user. Hash 'admin' with bcrypt and flag
            //    must_change_password = true so first login is gated.
            final String bcryptHash = org.mindrot.jbcrypt.BCrypt.hashpw(
                "admin", org.mindrot.jbcrypt.BCrypt.gensalt()
            );
            final int userId;
            try (PreparedStatement user = conn.prepareStatement(String.join(" ",
                "INSERT INTO users (username, password_hash, enabled,",
                "auth_provider, must_change_password)",
                "VALUES (?, ?, TRUE, 'local', TRUE) RETURNING id"
            ))) {
                user.setString(1, "admin");
                user.setString(2, bcryptHash);
                try (ResultSet rs = user.executeQuery()) {
                    if (!rs.next()) {
                        return;
                    }
                    userId = rs.getInt(1);
                }
            }
            // 4. Assign the admin role.
            try (PreparedStatement assign = conn.prepareStatement(String.join(" ",
                "INSERT INTO user_roles (user_id, role_id)",
                "SELECT ?, id FROM roles WHERE name = 'admin'",
                "ON CONFLICT DO NOTHING"
            ))) {
                assign.setInt(1, userId);
                assign.executeUpdate();
            }
            LOG.warn("Bootstrapped default admin user — username='admin' "
                + "password='admin' (must_change_password=TRUE on first login). "
                + "CHANGE THIS IMMEDIATELY in production.");
        } catch (final Exception ex) {
            LOG.error("Failed to bootstrap default admin user", ex);
        }
    }

    /**
     * Convert YAML mapping to JsonObject, including nested sequences and mappings.
     * @param yaml YAML mapping to convert
     * @return JsonObject representation
     */
    static JsonObject yamlToJson(final YamlMapping yaml) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        for (final YamlNode key : yaml.keys()) {
            final String keyStr = key.asScalar().value();
            final String scalar = yaml.string(keyStr);
            if (scalar != null) {
                final String resolved = resolveEnvVars(scalar);
                // Try to preserve booleans and numbers
                if ("true".equals(resolved) || "false".equals(resolved)) {
                    builder.add(keyStr, Boolean.parseBoolean(resolved));
                } else {
                    try {
                        builder.add(keyStr, Long.parseLong(resolved));
                    } catch (final NumberFormatException nfe) {
                        builder.add(keyStr, resolved);
                    }
                }
            } else {
                // Try sequence before mapping: eo-yaml may interpret
                // a sequence of single-key mappings (- key: val) as a
                // mapping, losing entries. Sequence check first is safer.
                final YamlSequence seq = yaml.yamlSequence(key);
                if (seq != null) {
                    builder.add(keyStr, yamlSeqToJson(seq));
                } else {
                    final YamlMapping nested = yaml.yamlMapping(key);
                    if (nested != null) {
                        builder.add(keyStr, yamlToJson(nested));
                    }
                }
            }
        }
        return builder.build();
    }

    /**
     * Convert YAML sequence to JsonArray, handling nested mappings, scalars,
     * and nested sequences.
     * @param seq YAML sequence to convert
     * @return JsonArray representation
     */
    private static javax.json.JsonArray yamlSeqToJson(final YamlSequence seq) {
        final JsonArrayBuilder arr = Json.createArrayBuilder();
        for (final YamlNode node : seq) {
            if (node.type() == Node.SCALAR) {
                arr.add(resolveEnvVars(node.asScalar().value()));
            } else if (node.type() == Node.MAPPING) {
                arr.add(yamlToJson(node.asMapping()));
            } else if (node.type() == Node.SEQUENCE) {
                arr.add(yamlSeqToJson(node.asSequence()));
            }
        }
        return arr.build();
    }

    /**
     * Resolve ${VAR_NAME} placeholders with actual environment variable values.
     * @param value String that may contain env var placeholders
     * @return Resolved string
     */
    private static String resolveEnvVars(final String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String result = value;
        final java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        while (matcher.find()) {
            final String envName = matcher.group(1);
            final String envVal = System.getenv(envName);
            if (envVal != null) {
                result = result.replace("${" + envName + "}", envVal);
            }
        }
        return result;
    }
}
