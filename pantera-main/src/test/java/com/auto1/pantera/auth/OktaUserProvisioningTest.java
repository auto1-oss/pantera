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
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.settings.users.CrudUsers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link OktaUserProvisioning}: the YAML policy storage and the
 * database must end up with the same user for the same Okta login.
 */
final class OktaUserProvisioningTest {

    /**
     * Okta group to Pantera role.
     */
    private static final Map<String, String> MAPPING = Map.of(
        "pantera_readers", "reader",
        "pantera_admins", "admin"
    );

    @Test
    void createsADatabaseUserOwnedByOkta() {
        final FakeUsers users = new FakeUsers();
        new OktaUserProvisioning(users, OktaUserProvisioningTest.MAPPING)
            .provision("alice", "alice@example.com", List.of("pantera_readers", "other"));
        final JsonObject alice = users.get("alice").orElseThrow();
        MatcherAssert.assertThat(
            "only the mapped group gives a role",
            FakeUsers.roles(alice), new IsEqual<>(Set.of("reader"))
        );
        MatcherAssert.assertThat(
            "email is stored", alice.getString("email"), new IsEqual<>("alice@example.com")
        );
        MatcherAssert.assertThat(
            "a new user belongs to the okta provider, so a later SSO sign-in binds to it",
            alice.getString("type"), new IsEqual<>("okta")
        );
    }

    @Test
    void addsMappedRolesToTheOnesADatabaseUserHas() {
        final FakeUsers users = new FakeUsers();
        users.addOrUpdate(
            Json.createObjectBuilder()
                .add("type", "okta")
                .add("roles", Json.createArrayBuilder().add("deployer"))
                .build(),
            "bob"
        );
        new OktaUserProvisioning(users, OktaUserProvisioningTest.MAPPING)
            .provision("bob", null, List.of("pantera_admins"));
        MatcherAssert.assertThat(
            FakeUsers.roles(users.get("bob").orElseThrow()),
            new IsEqual<>(Set.of("deployer", "admin"))
        );
    }

    @Test
    void leavesRolesOfADatabaseUserAloneWhenNothingIsMapped() {
        final FakeUsers users = new FakeUsers();
        users.addOrUpdate(
            Json.createObjectBuilder()
                .add("type", "okta")
                .add("roles", Json.createArrayBuilder().add("deployer"))
                .build(),
            "carol"
        );
        new OktaUserProvisioning(users, OktaUserProvisioningTest.MAPPING)
            .provision("carol", null, List.of("unmapped"));
        MatcherAssert.assertThat(
            "no role write at all: the DAO replaces the whole set when given one",
            users.roleWrites(), new IsEqual<>(1)
        );
    }

    @Test
    void neverTakesOverTheProviderOrEnablesAnExistingDatabaseUser() {
        final FakeUsers users = new FakeUsers();
        users.addOrUpdate(Json.createObjectBuilder().add("type", "local").build(), "dave");
        users.disable("dave");
        new OktaUserProvisioning(users, OktaUserProvisioningTest.MAPPING)
            .provision("dave", "dave@example.com", List.of("pantera_readers"));
        final JsonObject dave = users.get("dave").orElseThrow();
        MatcherAssert.assertThat(
            "the stored provider is kept", dave.getString("type"), new IsEqual<>("local")
        );
        MatcherAssert.assertThat(
            "a disabled user stays disabled",
            dave.getBoolean("enabled"), new IsEqual<>(false)
        );
    }

    @Test
    void givesTheSameRolesInBothBackends() throws IOException {
        final BlockingStorage asto = new BlockingStorage(new InMemoryStorage());
        asto.save(
            new Key.From("users/erin.yml"),
            "enabled: true\nroles:\n  - deployer\n".getBytes(StandardCharsets.UTF_8)
        );
        final FakeUsers users = new FakeUsers();
        users.addOrUpdate(
            Json.createObjectBuilder()
                .add("type", "okta")
                .add("roles", Json.createArrayBuilder().add("deployer"))
                .build(),
            "erin"
        );
        final List<String> groups = new ArrayList<>(
            List.of("pantera_readers", "pantera_admins", "unmapped")
        );
        groups.add(null);
        new OktaUserProvisioning(asto, OktaUserProvisioningTest.MAPPING)
            .provision("erin", "erin@example.com", groups);
        new OktaUserProvisioning(users, OktaUserProvisioningTest.MAPPING)
            .provision("erin", "erin@example.com", groups);
        final YamlMapping yaml = Yaml.createYamlInput(
            new String(asto.value(new Key.From("users/erin.yml")), StandardCharsets.UTF_8)
        ).readYamlMapping();
        final Set<String> fromYaml = new TreeSet<>();
        final YamlSequence seq = yaml.yamlSequence("roles");
        seq.values().forEach(node -> fromYaml.add(node.asScalar().value()));
        MatcherAssert.assertThat(
            "YAML policy storage: existing roles plus the mapped ones",
            fromYaml, new IsEqual<>(Set.of("deployer", "reader", "admin"))
        );
        MatcherAssert.assertThat(
            "database: the same set",
            FakeUsers.roles(users.get("erin").orElseThrow()), new IsEqual<>(fromYaml)
        );
    }

    @Test
    void keepsADisabledYamlUserDisabled() throws IOException {
        final BlockingStorage asto = new BlockingStorage(new InMemoryStorage());
        asto.save(
            new Key.From("users/frank.yml"),
            "enabled: false\n".getBytes(StandardCharsets.UTF_8)
        );
        new OktaUserProvisioning(asto, OktaUserProvisioningTest.MAPPING)
            .provision("frank", null, List.of("pantera_readers"));
        MatcherAssert.assertThat(
            Yaml.createYamlInput(
                new String(asto.value(new Key.From("users/frank.yml")), StandardCharsets.UTF_8)
            ).readYamlMapping().string("enabled"),
            new IsEqual<>("false")
        );
    }

    /**
     * Users store with the semantics of {@code UserDao}: an upsert keeps
     * what it is not given, a given role list replaces the stored one,
     * {@code enabled} is only changed by enable/disable.
     */
    private static final class FakeUsers implements CrudUsers {

        /**
         * Users by name.
         */
        private final Map<String, JsonObject> docs = new ConcurrentHashMap<>();

        /**
         * Number of upserts that carried a role list.
         */
        private int writes;

        static Set<String> roles(final JsonObject user) {
            final Set<String> res = new TreeSet<>();
            user.getJsonArray("roles").getValuesAs(JsonString.class)
                .forEach(role -> res.add(role.getString()));
            return res;
        }

        int roleWrites() {
            return this.writes;
        }

        @Override
        public JsonArray list() {
            return Json.createArrayBuilder().build();
        }

        @Override
        public Optional<JsonObject> get(final String uname) {
            return Optional.ofNullable(this.docs.get(uname));
        }

        @Override
        public void addOrUpdate(final JsonObject info, final String uname) {
            final JsonObjectBuilder merged = Json.createObjectBuilder(
                this.docs.getOrDefault(
                    uname,
                    Json.createObjectBuilder()
                        .add("enabled", true)
                        .add("roles", Json.createArrayBuilder())
                        .build()
                )
            );
            info.forEach(merged::add);
            if (info.containsKey("roles")) {
                this.writes += 1;
            }
            this.docs.put(uname, merged.build());
        }

        @Override
        public void disable(final String uname) {
            this.docs.put(
                uname,
                Json.createObjectBuilder(this.docs.get(uname)).add("enabled", false).build()
            );
        }

        @Override
        public void enable(final String uname) {
            throw new UnsupportedOperationException("provisioning must never enable a user");
        }

        @Override
        public void remove(final String uname) {
            this.docs.remove(uname);
        }

        @Override
        public void alterPassword(final String uname, final JsonObject info) {
            throw new UnsupportedOperationException();
        }
    }
}
