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
package com.auto1.pantera.security.policy;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.security.perms.EmptyPermissions;
import java.nio.charset.StandardCharsets;
import java.security.Permissions;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link CachedYamlPolicy#rolePermissions(BlockingStorage, String)} method.
 * @since 1.2
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class YamlPolicyAstoRolesTest {

    /**
     * Test storage.
     */
    private BlockingStorage asto;

    @BeforeEach
    void init() {
        this.asto = new BlockingStorage(new InMemoryStorage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"java-dev.yml", "java-dev.yaml"})
    void readsRolePermissions(final String key) {
        this.asto.save(new Key.From("roles", key), this.javaDev());
        MatcherAssert.assertThat(
            CachedYamlPolicy.rolePermissions(this.asto, "java-dev"),
            new IsInstanceOf(Permissions.class)
        );
    }

    @Test
    void readsAdminPermissions() {
        this.asto.save(new Key.From("roles/admin.yaml"), this.admin());
        MatcherAssert.assertThat(
            CachedYamlPolicy.rolePermissions(this.asto, "admin"),
            new IsInstanceOf(Permissions.class)
        );
    }

    @Test
    void returnsEmptyPermissionsIfRoleDisabled() {
        this.asto.save(new Key.From("roles/some-role.yaml"), this.disabled());
        MatcherAssert.assertThat(
            CachedYamlPolicy.rolePermissions(this.asto, "some-role"),
            new IsInstanceOf(EmptyPermissions.class)
        );
    }

    @Test
    void returnsEmptyPermissionsIfFileDoesNotExists() {
        MatcherAssert.assertThat(
            CachedYamlPolicy.rolePermissions(this.asto, "any"),
            new IsInstanceOf(EmptyPermissions.class)
        );
    }

    private byte[] javaDev() {
        return String.join(
            "\n",
            "permissions:",
            "  adapter_basic_permissions:",
            "    maven-repo:",
            "      - read",
            "      - write",
            "    python-repo:",
            "      - read",
            "    npm-repo:",
            "      - read"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] admin() {
        return String.join(
            "\n",
            "permissions:",
            "  all_permission: {}"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] disabled() {
        return String.join(
            "\n",
            "enabled: false",
            "permissions:",
            "  adapter_basic_permissions:",
            "    maven-repo:",
            "      - read",
            "      - write",
            "    python-repo:",
            "      - read",
            "    npm-repo:",
            "      - read"
        ).getBytes(StandardCharsets.UTF_8);
    }

}
