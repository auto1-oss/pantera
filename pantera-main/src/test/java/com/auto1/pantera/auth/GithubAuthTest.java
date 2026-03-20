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
package com.auto1.pantera.auth;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.http.auth.AuthUser;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test case for {@link GithubAuth}.
 *
 * @since 0.10
 */
final class GithubAuthTest {

    @Test
    void resolveUserByToken() {
        final String secret = "secret";
        MatcherAssert.assertThat(
            new GithubAuth(
                token -> {
                    if (token.equals(secret)) {
                        return "User";
                    }
                    return "";
                }
            ).user("github.com/UsEr", secret).orElseThrow(),
            new IsEqual<>(new AuthUser("UsEr", "test"))
        );
    }

    @Test
    void shouldReturnOptionalEmptyWhenRequestIsUnauthorized() {
        MatcherAssert.assertThat(
            new GithubAuth(
                token -> {
                    throw new AssertionError(
                        String.join(
                            "HTTP response status is not equal to 200:\n",
                            "401 Unauthorized [https://api.github.com/user]"
                        )
                    );
                }
            ).user("github.com/bad_user", "bad_secret"),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void shouldThrownExceptionWhenAssertionErrorIsHappened() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new GithubAuth(
                token -> {
                    throw new AssertionError("Any error");
                }
            ).user("github.com/user", "pwd")
        );
    }
}
