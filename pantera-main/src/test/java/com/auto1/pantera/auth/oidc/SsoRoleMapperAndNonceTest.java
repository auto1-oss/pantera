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
package com.auto1.pantera.auth.oidc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for SSO group→role mapping (default-deny) and
 * the auth-request nonce store (SecOps sso-oidc).
 *
 * <p>Before 2.2.9 an IdP group with no explicit {@code group-roles} mapping
 * was used verbatim as a Pantera role name — so any IdP group named
 * {@code admin} granted the bootstrap {@code admin} role (all permissions)
 * to whoever the IdP put in it. Unmapped groups must grant nothing.</p>
 *
 * @since 2.2.9
 */
final class SsoRoleMapperAndNonceTest {

    @Test
    void unmappedGroupGrantsNoRole() {
        final List<String> roles = SsoRoleMapper.map(
            List.of("admin", "pantera_readers"),
            Map.of("pantera_readers", "reader"),
            "reader"
        );
        MatcherAssert.assertThat(
            "an IdP group with no explicit mapping must NOT become a role — 'admin' is not granted",
            roles, new IsEqual<>(List.of("reader"))
        );
    }

    @Test
    void explicitMappingsAreHonoured() {
        MatcherAssert.assertThat(
            "explicitly mapped groups map to their configured roles",
            SsoRoleMapper.map(
                List.of("pantera_admins", "pantera_readers"),
                Map.of("pantera_admins", "admin", "pantera_readers", "reader"),
                "reader"
            ),
            new IsEqual<>(List.of("admin", "reader"))
        );
    }

    @Test
    void groupsWithoutAnyMatchFallToDefaultRole() {
        MatcherAssert.assertThat(
            "groups present but none mapped → the configured default role only",
            SsoRoleMapper.map(List.of("engineering"), Map.of("ops", "admin"), "reader"),
            new IsEqual<>(List.of("reader"))
        );
    }

    @Test
    void noMappingConfiguredNeverPromotesGroupNames() {
        MatcherAssert.assertThat(
            "with no group-roles configured at all, raw group names must still not become roles",
            SsoRoleMapper.map(List.of("admin"), Map.of(), "reader"),
            new IsEqual<>(List.of("reader"))
        );
    }

    @Test
    void nonceIsSingleUseAndBoundToState() {
        final AtomicLong now = new AtomicLong();
        final SsoNonceStore store = new SsoNonceStore(Duration.ofMinutes(10), now::get);
        final String nonce = store.issue("state-abc");
        MatcherAssert.assertThat(
            "a nonce is returned once for its state",
            store.consume("state-abc"), new IsEqual<>(Optional.of(nonce))
        );
        MatcherAssert.assertThat(
            "a second consume of the same state must fail (single use)",
            store.consume("state-abc"), new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "an unknown state yields no nonce",
            store.consume("never-issued"), new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void nonceExpires() {
        final AtomicLong now = new AtomicLong();
        final SsoNonceStore store = new SsoNonceStore(Duration.ofMinutes(10), now::get);
        store.issue("state-old");
        now.addAndGet(Duration.ofMinutes(11).toNanos());
        MatcherAssert.assertThat(
            "an expired auth request must not be completable",
            store.consume("state-old"), new IsEqual<>(Optional.empty())
        );
    }
}
