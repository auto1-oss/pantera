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
package com.auto1.pantera.security.perms;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.PanteraException;
import java.security.AllPermission;
import java.util.Collections;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PermissionsLoader}.
 * @since 1.2
 */
class PermissionsTest {

    @Test
    void createsBasicPermission() {
        MatcherAssert.assertThat(
            new PermissionsLoader().newObject(
                "adapter_basic_permissions",
                new PermissionConfig.FromYamlMapping(
                    Yaml.createYamlMappingBuilder()
                        .add("my-repo", Yaml.createYamlSequenceBuilder().add("read").build())
                        .build()
                )
            ).elements().nextElement(),
            new IsInstanceOf(AdapterBasicPermission.class)
        );
    }

    @Test
    void createsAllPermission() {
        MatcherAssert.assertThat(
            new PermissionsLoader().newObject(
                "all_permission",
                new PermissionConfig.FromYamlMapping(Yaml.createYamlMappingBuilder().build())
            ).elements().nextElement(),
            new IsInstanceOf(AllPermission.class)
        );
    }

    @Test
    void throwsExceptionIfPermNotFound() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new PermissionsLoader().newObject(
                "unknown_perm",
                new PermissionConfig.FromYamlMapping(Yaml.createYamlMappingBuilder().build())
            )
        );
    }

    @Test
    void throwsExceptionIfPermissionsHaveTheSameName() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new PermissionsLoader(
                Collections.singletonMap(
                    PermissionsLoader.SCAN_PACK, "adapter.perms.docker;adapter.perms.duplicate"
                )
            )
        );
    }

    @Test
    void createsExternalPermissions() {
        final PermissionsLoader permissions = new PermissionsLoader(
            Collections.singletonMap(
                PermissionsLoader.SCAN_PACK, "adapter.perms.docker;adapter.perms.maven"
            )
        );
        MatcherAssert.assertThat(
            "Maven permission was created",
            permissions.newObject(
                "maven-perm",
                new PermissionConfig.FromYamlMapping(Yaml.createYamlMappingBuilder().build())
            ).elements().nextElement(),
            new IsInstanceOf(AllPermission.class)
        );
        MatcherAssert.assertThat(
            "Docker permission was created",
            permissions.newObject(
                "docker-perm",
                new PermissionConfig.FromYamlMapping(Yaml.createYamlMappingBuilder().build())
            ).elements().nextElement(),
            new IsInstanceOf(AllPermission.class)
        );
    }

}
