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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for SecOps bootstrap-admin: the fallback admin
 * user must NOT be seeded with the source-known password {@code admin}.
 *
 * <p>Before 2.2.9 {@code bootstrapDefaultADmin} hard-coded {@code admin/admin}
 * (with {@code must_change_password=true}, enforced only in the UI), so on any
 * DB-backed deployment {@code admin/admin} was immediately usable for full
 * {@code all_permission} API access. The password is now taken from
 * {@code PANTERA_BOOTSTRAP_ADMIN_PASSWORD} when set, or securely randomised and
 * logged once — never the literal {@code admin}.</p>
 *
 * @since 2.2.9
 */
final class BootstrapAdminPasswordTest {

    private final YamlToDbMigrator migrator =
        new YamlToDbMigrator(null, null, null, null);

    @Test
    void configuredPasswordIsHonoured() {
        MatcherAssert.assertThat(
            "an explicitly configured bootstrap password must be used verbatim",
            this.migrator.resolveBootstrapPassword("s3cret-configured"),
            new IsEqual<>("s3cret-configured")
        );
    }

    @Test
    void unsetPasswordIsNeverTheKnownDefault() {
        MatcherAssert.assertThat(
            "an unset bootstrap password must NOT fall back to the literal 'admin'",
            this.migrator.resolveBootstrapPassword(null),
            new IsNot<>(new IsEqual<>("admin"))
        );
    }

    @Test
    void unsetPasswordIsHighEntropyAndUnique() {
        final String first = this.migrator.resolveBootstrapPassword(null);
        final String second = this.migrator.resolveBootstrapPassword(null);
        MatcherAssert.assertThat(
            "a generated bootstrap password must be at least 16 chars",
            first.length() >= 16, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "two generated bootstrap passwords must differ (randomised, not fixed)",
            first.equals(second), new IsEqual<>(false)
        );
    }

    @Test
    void blankConfiguredPasswordIsTreatedAsUnset() {
        MatcherAssert.assertThat(
            "a blank configured password must not be used — fall back to a generated one",
            this.migrator.resolveBootstrapPassword("   "),
            new IsNot<>(new IsEqual<>("   "))
        );
    }
}
