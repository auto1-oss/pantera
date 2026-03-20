/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client.auth;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OAuthTokenFormat}.
 *
 * @since 0.5
 */
class OAuthTokenFormatTest {

    @Test
    void shouldReadAccessToken() {
        MatcherAssert.assertThat(
            new OAuthTokenFormat().token(
                String.join(
                    "\n",
                    "{",
                    "\"access_token\":\"mF_9.B5f-4.1JqM\",",
                    "\"token_type\":\"Bearer\",",
                    "\"expires_in\":3600,",
                    "\"refresh_token\":\"tGzv3JOkF0XG5Qx2TlKWIA\"",
                    "}"
                ).getBytes()
            ),
            new IsEqual<>("mF_9.B5f-4.1JqM")
        );
    }

    @Test
    void shouldReadDockerTokenField() {
        MatcherAssert.assertThat(
            new OAuthTokenFormat().token(
                "{\"token\":\"dhi-registry-token-value\"}".getBytes()
            ),
            new IsEqual<>("dhi-registry-token-value")
        );
    }

    @Test
    void shouldPreferAccessTokenOverToken() {
        MatcherAssert.assertThat(
            new OAuthTokenFormat().token(
                "{\"access_token\":\"preferred\",\"token\":\"fallback\"}".getBytes()
            ),
            new IsEqual<>("preferred")
        );
    }
}
