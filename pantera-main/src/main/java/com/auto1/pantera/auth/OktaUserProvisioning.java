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
package com.auto1.pantera.auth;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.amihaiemil.eoyaml.YamlSequenceBuilder;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.users.CrudUsers;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;

/**
 * Just-in-time provisioning of users and role mappings for Okta-authenticated users.
 *
 * <p>Two backends with the same outcome: the YAML policy storage
 * ({@code users/<name>.yml}, DB-less mode) and the users table (through
 * {@link CrudUsers}, when a database is configured and authorization is
 * DB-backed). In both, the roles mapped from the user's Okta groups are
 * added to the roles the user already has, an unmapped group gives nothing,
 * the email is updated, and the enabled state of an existing user is never
 * changed.</p>
 */
public final class OktaUserProvisioning {

    /**
     * Where users live.
     */
    private final Store store;

    /**
     * Okta group to Pantera role.
     */
    private final Map<String, String> groupRoles;

    /**
     * Provision into the YAML policy storage.
     * @param asto Policy storage
     * @param groupRoles Okta group to Pantera role
     */
    public OktaUserProvisioning(final BlockingStorage asto,
        final Map<String, String> groupRoles) {
        this(new YamlStore(asto), groupRoles);
    }

    /**
     * Provision into the users table.
     * @param users Users of the database
     * @param groupRoles Okta group to Pantera role
     */
    public OktaUserProvisioning(final CrudUsers users,
        final Map<String, String> groupRoles) {
        this(new DbStore(users), groupRoles);
    }

    /**
     * Primary ctor.
     * @param store Where users live
     * @param groupRoles Okta group to Pantera role
     */
    private OktaUserProvisioning(final Store store, final Map<String, String> groupRoles) {
        this.store = store;
        this.groupRoles = groupRoles;
    }

    /**
     * Create or update the user after a successful Okta authentication.
     * A failure to write is logged and does not fail the authentication.
     * @param username User name
     * @param email Email, may be null
     * @param groups Okta groups of the user, may be null
     */
    public void provision(final String username, final String email, final Collection<String> groups) {
        final Set<String> mapped = new LinkedHashSet<>();
        if (groups != null) {
            for (final String grp : groups) {
                if (grp == null) {
                    continue;
                }
                final String role = this.groupRoles.get(grp);
                if (role != null && !role.isEmpty()) {
                    mapped.add(role);
                }
            }
        }
        try {
            this.store.save(username, email, mapped);
        } catch (final IOException | RuntimeException err) {
            EcsLogger.error("com.auto1.pantera.auth")
                .message("Failed to provision Okta user")
                .eventCategory("authentication")
                .eventAction("user_provision")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(err)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Users backend.
     */
    private interface Store {

        /**
         * Create or update a user.
         * @param username User name
         * @param email Email, may be null
         * @param mapped Roles mapped from the Okta groups, to add
         * @throws IOException When the write fails
         */
        void save(String username, String email, Set<String> mapped) throws IOException;
    }

    /**
     * Users table. {@code CrudUsers.addOrUpdate} keeps every column it is
     * not given and replaces the whole role set when given one, so the role
     * list is sent only when it grows, as the union with the stored roles.
     */
    private static final class DbStore implements Store {

        /**
         * Users.
         */
        private final CrudUsers users;

        DbStore(final CrudUsers users) {
            this.users = users;
        }

        @Override
        public void save(final String username, final String email, final Set<String> mapped) {
            final Optional<JsonObject> existing = this.users.get(username);
            final Set<String> roles = new LinkedHashSet<>();
            existing
                .filter(user -> user.containsKey("roles") && !user.isNull("roles"))
                .ifPresent(
                    user -> user.getJsonArray("roles").getValuesAs(JsonString.class)
                        .forEach(role -> roles.add(role.getString()))
                );
            final boolean grows = roles.addAll(mapped);
            final JsonObjectBuilder info = Json.createObjectBuilder();
            if (existing.isEmpty()) {
                // A new user belongs to Okta: a later SSO sign-in binds to
                // it. The provider of an existing user is never taken over.
                info.add("type", "okta");
            }
            if (email != null && !email.isEmpty()) {
                info.add("email", email);
            }
            if (grows) {
                final JsonArrayBuilder arr = Json.createArrayBuilder();
                roles.forEach(arr::add);
                info.add("roles", arr);
            }
            final JsonObject doc = info.build();
            if (!doc.isEmpty()) {
                this.users.addOrUpdate(doc, username);
            }
        }
    }

    /**
     * YAML policy storage: {@code users/<name>.yml} (or {@code .yaml}).
     */
    private static final class YamlStore implements Store {

        /**
         * Policy storage.
         */
        private final BlockingStorage asto;

        YamlStore(final BlockingStorage asto) {
            this.asto = asto;
        }

        @Override
        public void save(final String username, final String email, final Set<String> mapped)
            throws IOException {
            final Key yaml = new Key.From(String.format("users/%s.yaml", username));
            final Key yml = new Key.From(String.format("users/%s.yml", username));
            final Key target;
            final YamlMapping existing;
            if (this.asto.exists(yaml)) {
                target = yaml;
                existing = readYaml(this.asto.value(yaml));
            } else if (this.asto.exists(yml)) {
                target = yml;
                existing = readYaml(this.asto.value(yml));
            } else {
                target = yml;
                existing = Yaml.createYamlMappingBuilder().build();
            }
            YamlMappingBuilder builder = Yaml.createYamlMappingBuilder();
            for (final YamlNode key : existing.keys()) {
                final String name = key.asScalar().value();
                if (!"roles".equals(name) && !"enabled".equals(name) && !"email".equals(name)) {
                    builder = builder.add(name, existing.value(name));
                }
            }
            // SECURITY (ROPC): enable only on first creation. For a user that
            // already exists, preserve the stored enabled state so that
            // re-provisioning through the password grant never silently
            // re-enables a disabled account. A missing value (brand-new user
            // or a legacy file without the field) defaults to enabled.
            final String enabled = existing.string("enabled");
            builder = builder.add("enabled", enabled == null ? "true" : enabled);
            if (email != null && !email.isEmpty()) {
                builder = builder.add("email", email);
            }
            final Set<String> roles = new LinkedHashSet<>();
            final YamlSequence stored = existing.yamlSequence("roles");
            if (stored != null) {
                stored.values().forEach(node -> roles.add(node.asScalar().value()));
            }
            roles.addAll(mapped);
            if (!roles.isEmpty()) {
                YamlSequenceBuilder seq = Yaml.createYamlSequenceBuilder();
                for (final String role : roles) {
                    seq = seq.add(role);
                }
                builder = builder.add("roles", seq.build());
            }
            this.asto.save(target, builder.build().toString().getBytes(StandardCharsets.UTF_8));
        }

        private static YamlMapping readYaml(final byte[] data) throws IOException {
            return Yaml.createYamlInput(new ByteArrayInputStream(data)).readYamlMapping();
        }
    }
}
