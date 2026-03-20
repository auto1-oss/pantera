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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Authorization.Basic}.
 *
 * @since 0.12
 */
public final class AuthorizationBasicTest {

    @Test
    void shouldHaveExpectedValue() {
        MatcherAssert.assertThat(
            new Authorization.Basic("Aladdin", "open sesame").getValue(),
            new IsEqual<>("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
        );
    }

    @Test
    void shouldHaveExpectedCredentials() {
        final String credentials = "123.abc";
        MatcherAssert.assertThat(
            new Authorization.Basic(credentials).credentials(),
            new IsEqual<>(credentials)
        );
    }

    @Test
    void shouldHaveExpectedUsername() {
        MatcherAssert.assertThat(
            new Authorization.Basic("YWxpY2U6b3BlbiBzZXNhbWU=").username(),
            new IsEqual<>("alice")
        );
    }

    @Test
    void shouldHaveExpectedPassword() {
        MatcherAssert.assertThat(
            new Authorization.Basic("QWxhZGRpbjpxd2VydHk=").password(),
            new IsEqual<>("qwerty")
        );
    }
    
    @Test
    void shouldThrowOnEmptyCredentials() {
        // Empty credentials string fails at regex parsing level
        final Authorization.Basic basic = new Authorization.Basic("");
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            basic::username,
            "Should throw exception on empty credentials"
        );
    }
    
    @Test
    void shouldThrowOnMissingPassword() {
        // Base64("alice") = "YWxpY2U=" (no colon, no password)
        final Authorization.Basic basic = new Authorization.Basic("YWxpY2U=");
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            basic::password,
            "Should throw IllegalArgumentException when password is missing"
        );
    }
    
    @Test
    void shouldHandleUsernameWithoutPassword() {
        // Base64("alice") = "YWxpY2U=" (no colon, no password)
        final Authorization.Basic basic = new Authorization.Basic("YWxpY2U=");
        MatcherAssert.assertThat(
            "Should extract username even without password",
            basic.username(),
            new IsEqual<>("alice")
        );
    }
    
    @Test
    void shouldHandlePasswordWithColon() {
        // Base64("alice:pass:word") = "YWxpY2U6cGFzczp3b3Jk"
        final Authorization.Basic basic = new Authorization.Basic("YWxpY2U6cGFzczp3b3Jk");
        MatcherAssert.assertThat(
            "Password should include everything after first colon",
            basic.password(),
            new IsEqual<>("pass:word")
        );
    }
}
