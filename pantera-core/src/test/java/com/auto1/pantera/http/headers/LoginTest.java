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
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

final class LoginTest {

    @Test
    void fallsBackToBasicAuthorization() {
        final String encoded = Base64.getEncoder()
            .encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
        final Login login = new Login(Headers.from("Authorization", "Basic " + encoded));
        MatcherAssert.assertThat(login.getValue(), Matchers.is("alice"));
    }
}
