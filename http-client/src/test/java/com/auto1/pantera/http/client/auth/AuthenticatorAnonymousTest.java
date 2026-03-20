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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.hamcrest.MatcherAssert;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Authenticator#ANONYMOUS}.
 *
 * @since 0.4
 */
class AuthenticatorAnonymousTest {

    @Test
    void shouldProduceEmptyHeader() {
        MatcherAssert.assertThat(
            StreamSupport.stream(
                Authenticator.ANONYMOUS.authenticate(Headers.EMPTY)
                    .toCompletableFuture().join()
                    .spliterator(),
                false
            ).map(Header::new).collect(Collectors.toList()),
            new IsEmptyCollection<>()
        );
    }
}
