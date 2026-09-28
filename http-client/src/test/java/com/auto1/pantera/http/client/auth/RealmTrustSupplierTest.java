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
package com.auto1.pantera.http.client.auth;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link RealmTrust} reads its host allowlist through a supplier on every
 * decision, so an allowlist edited at runtime applies to the next bearer
 * challenge without re-wiring the authenticator.
 *
 * @since 2.2.9
 */
final class RealmTrustSupplierTest {

    @Test
    void allowlistChangesApplyToTheNextDecision() {
        final AtomicReference<Set<String>> hosts = new AtomicReference<>(Set.of());
        final RealmTrust trust = new RealmTrust(
            URI.create("https://registry.example.com/v2/"), hosts::get
        );
        final URI realm = URI.create("https://Auth.Other.NET/token");
        MatcherAssert.assertThat(
            "a foreign realm is refused while the allowlist is empty",
            trust.trusts(realm), new IsEqual<>(false)
        );
        hosts.set(Set.of("auth.other.net"));
        MatcherAssert.assertThat(
            "the same realm is trusted once its host is allow-listed (case-insensitive)",
            trust.trusts(realm), new IsEqual<>(true)
        );
    }
}
