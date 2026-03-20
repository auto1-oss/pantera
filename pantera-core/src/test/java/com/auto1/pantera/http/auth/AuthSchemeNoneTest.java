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
package com.auto1.pantera.http.auth;

import com.auto1.pantera.http.Headers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AuthScheme#NONE}.
 *
 * @since 0.18
 */
final class AuthSchemeNoneTest {

    @Test
    void shouldAuthEmptyHeadersAsAnonymous() {
        Assertions.assertTrue(
            AuthScheme.NONE.authenticate(Headers.EMPTY)
            .toCompletableFuture().join()
            .user().isAnonymous()
        );
    }
}
