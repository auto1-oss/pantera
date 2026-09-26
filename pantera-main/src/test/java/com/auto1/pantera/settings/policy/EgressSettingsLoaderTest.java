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
package com.auto1.pantera.settings.policy;

import com.auto1.pantera.http.client.egress.EgressSettingsRegistry;
import java.util.Map;
import java.util.Set;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@link EgressSettingsLoader}: DB row, then environment, then defaults for
 * the outbound egress policy, and its bridge into http-client's registry.
 *
 * @since 2.2.9
 */
final class EgressSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        EgressSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        final EgressConfig resolved = EgressSettingsLoader.activeSupplier().get();
        MatcherAssert.assertThat("not strict by default", resolved.blockPrivate(), new IsEqual<>(false));
        MatcherAssert.assertThat("no allow hosts", resolved.allowHosts(), new IsEqual<>(Set.of()));
        MatcherAssert.assertThat(
            "no credential hosts", resolved.credentialAllowHosts(), new IsEqual<>(Set.of())
        );
    }

    @Test
    void dbRowWinsOverEnvironment() {
        final EgressSettingsLoader loader = new EgressSettingsLoader(
            FakeAuthSettings.withRows(Map.of(
                "egress_block_private", "true",
                "egress_allow_hosts", "Mirror.Example, other.example",
                "upstream_credential_allow_hosts", "auth.example"
            )),
            Map.of("PANTERA_EGRESS_BLOCK_PRIVATE", "false")::get
        );
        MatcherAssert.assertThat("strict from the row", loader.get().blockPrivate(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "hosts normalised from the row",
            loader.get().allowHosts(), new IsEqual<>(Set.of("mirror.example", "other.example"))
        );
        MatcherAssert.assertThat(
            "credential hosts from the row",
            loader.get().credentialAllowHosts(), new IsEqual<>(Set.of("auth.example"))
        );
        MatcherAssert.assertThat(
            "the derived policy is strict too", loader.get().policy().strict(), new IsEqual<>(true)
        );
    }

    @Test
    void envTierWinsOverDefaultWhenNoRow() {
        final EgressSettingsLoader loader = new EgressSettingsLoader(
            FakeAuthSettings.empty(),
            Map.of(
                "PANTERA_EGRESS_BLOCK_PRIVATE", "true",
                "PANTERA_EGRESS_ALLOW_HOSTS", "a.example,b.example",
                "PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS", "c.example"
            )::get
        );
        MatcherAssert.assertThat("strict from env", loader.get().blockPrivate(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "allow hosts from env", loader.get().allowHosts(), new IsEqual<>(Set.of("a.example", "b.example"))
        );
        MatcherAssert.assertThat(
            "credential hosts from env", loader.get().credentialAllowHosts(), new IsEqual<>(Set.of("c.example"))
        );
    }

    @Test
    void installFeedsTheHttpClientRegistry() {
        EgressSettingsLoader.install(
            FakeAuthSettings.withRows(Map.of(
                "egress_block_private", "true",
                "upstream_credential_allow_hosts", "auth.example"
            )),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "the registry policy follows the installed loader",
            EgressSettingsRegistry.policy().get().strict(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the registry credential hosts follow the installed loader",
            EgressSettingsRegistry.credentialAllowHosts().get(), new IsEqual<>(Set.of("auth.example"))
        );
        EgressSettingsLoader.uninstall();
        MatcherAssert.assertThat(
            "uninstall detaches the registry as well",
            EgressSettingsRegistry.credentialAllowHosts().get().contains("auth.example"),
            new IsEqual<>(false)
        );
    }

    @Test
    void configRejectsMalformedHosts() {
        Assertions.assertThrows(IllegalArgumentException.class,
            (Executable) () -> new EgressConfig(false, "http://not a host", ""));
        Assertions.assertThrows(IllegalArgumentException.class,
            (Executable) () -> new EgressConfig(false, "", "bad/host"));
    }


    @Test
    void invalidAllowHostsKeepsBlockPrivate() {
        // A malformed host list must not silently switch off the strict
        // egress mode configured alongside it.
        final EgressSettingsLoader loader = new EgressSettingsLoader(
            FakeAuthSettings.withRows(Map.of(
                "egress_block_private", "true",
                "egress_allow_hosts", "bad host!"
            )),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "block-private is KEPT despite the invalid sibling key",
            loader.get().blockPrivate(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the invalid host list falls back to its own default (empty)",
            loader.get().allowHosts().isEmpty(), new IsEqual<>(true)
        );
    }
}
