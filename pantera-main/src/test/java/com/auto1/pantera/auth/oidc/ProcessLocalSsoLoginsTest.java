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
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProcessLocalSsoLogins}.
 * @since 2.2.9
 */
final class ProcessLocalSsoLoginsTest {

    @Test
    void callbackOnAnotherVerticleInstanceFindsTheLogin() {
        // Each AsyncApiVerticle instance builds its own AuthHandler, and each
        // handler asks for a store. /redirect lands on one instance, the IdP
        // callback on another: both must see the same pending login.
        final Duration ttl = Duration.ofMinutes(7);
        final SsoLoginStateStore redirect = ProcessLocalSsoLogins.store(ttl);
        final SsoLoginStateStore callback = ProcessLocalSsoLogins.store(ttl);
        final String state = redirect.newState();
        final String nonce = redirect.issue(state).toCompletableFuture().join();
        MatcherAssert.assertThat(
            callback.consume(state).toCompletableFuture().join(),
            new IsEqual<>(Optional.of(nonce))
        );
    }
}
