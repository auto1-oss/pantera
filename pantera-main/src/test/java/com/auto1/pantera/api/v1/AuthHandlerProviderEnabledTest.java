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
package com.auto1.pantera.api.v1;

import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for SecOps sso-oidc (disabled-provider sub-finding):
 * a disabled SSO provider must not be usable for login. Before 2.2.9
 * {@code findProvider} matched purely on type and ignored the provider's
 * {@code enabled} flag, so an admin who disabled an SSO provider left its
 * OAuth callback fully functional.
 *
 * @since 2.2.9
 */
final class AuthHandlerProviderEnabledTest {

    private final AuthHandler handler = new AuthHandler(null, null, null, null, null);

    @Test
    void disabledProviderIsNotUsable() {
        MatcherAssert.assertThat(
            "an SSO provider with enabled=false must not be usable for login",
            this.handler.providerEnabled(
                Json.createObjectBuilder().add("type", "okta").add("enabled", false).build()
            ),
            new IsEqual<>(false)
        );
    }

    @Test
    void enabledProviderIsUsable() {
        MatcherAssert.assertThat(
            "an SSO provider with enabled=true remains usable",
            this.handler.providerEnabled(
                Json.createObjectBuilder().add("type", "okta").add("enabled", true).build()
            ),
            new IsEqual<>(true)
        );
    }

    @Test
    void providerWithoutFlagDefaultsToUsable() {
        MatcherAssert.assertThat(
            "a provider without an explicit enabled flag defaults to usable (back-compat)",
            this.handler.providerEnabled(Json.createObjectBuilder().add("type", "okta").build()),
            new IsEqual<>(true)
        );
    }

    @Test
    void nullProviderIsNotUsable() {
        MatcherAssert.assertThat(
            "a missing provider is not usable",
            this.handler.providerEnabled(null), new IsEqual<>(false)
        );
    }
}
