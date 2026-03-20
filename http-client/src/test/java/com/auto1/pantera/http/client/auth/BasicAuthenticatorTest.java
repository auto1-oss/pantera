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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Authenticator} instances.
 *
 * @since 0.3
 */
class BasicAuthenticatorTest {

    @Test
    void shouldProduceBasicHeader() {
        MatcherAssert.assertThat(
            StreamSupport.stream(
                new BasicAuthenticator("Aladdin", "open sesame")
                    .authenticate(Headers.EMPTY)
                    .toCompletableFuture().join()
                    .spliterator(),
                false
            ).map(Header::new).collect(Collectors.toList()),
            Matchers.contains(new Header("Authorization", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="))
        );
    }
}
