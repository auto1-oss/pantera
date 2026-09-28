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
package com.auto1.pantera.http.client.egress;

import java.util.Set;
import java.util.function.Supplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Specification of {@link EgressSettingsRegistry}: the supplier handed to
 * an outbound-HTTP consumer at construction time must keep reflecting the
 * installed (DB-backed) settings afterwards, and revert to the
 * environment-derived defaults once nothing is installed.
 *
 * @since 2.2.9
 */
final class EgressSettingsRegistryTest {

    @AfterEach
    void tearDown() {
        EgressSettingsRegistry.uninstall();
    }

    @Test
    void supplierObtainedBeforeInstallSeesInstalledSettings() {
        final Supplier<EgressPolicy> policy = EgressSettingsRegistry.policy();
        final Supplier<Set<String>> hosts = EgressSettingsRegistry.credentialAllowHosts();
        EgressSettingsRegistry.install(
            () -> new EgressPolicy(true, Set.of("mirror.example")),
            () -> Set.of("auth.example")
        );
        MatcherAssert.assertThat(
            "the policy supplier must be live, not a snapshot",
            policy.get().strict(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the credential-host supplier must be live, not a snapshot",
            hosts.get().contains("auth.example"), new IsEqual<>(true)
        );
    }

    @Test
    void uninstallRevertsToEnvironmentDefaults() {
        EgressSettingsRegistry.install(
            () -> new EgressPolicy(true, Set.of()), () -> Set.of("auth.example")
        );
        EgressSettingsRegistry.uninstall();
        MatcherAssert.assertThat(
            "without an installed supplier the environment tier applies",
            EgressSettingsRegistry.policy().get().strict(),
            new IsEqual<>(EgressPolicy.fromEnvironment().strict())
        );
        MatcherAssert.assertThat(
            "credential hosts fall back to the environment tier too",
            EgressSettingsRegistry.credentialAllowHosts().get().contains("auth.example"),
            new IsEqual<>(false)
        );
    }
}
