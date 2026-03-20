/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client.auth;

import java.io.ByteArrayInputStream;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Authentication token response.
 * Supports both RFC 6750 {@code access_token} field and Docker Registry
 * Token Authentication {@code token} field. Many registries (DHI, GCR)
 * only return {@code token}.
 *
 * @since 0.5
 */
final class OAuthTokenFormat implements TokenFormat {

    @Override
    public String token(final byte[] content) {
        final JsonObject json = Json.createReader(new ByteArrayInputStream(content))
            .readObject();
        final String accessToken = json.getString("access_token", null);
        if (accessToken != null) {
            return accessToken;
        }
        final String token = json.getString("token", null);
        if (token != null) {
            return token;
        }
        throw new IllegalStateException(
            "Token response contains neither 'access_token' nor 'token' field"
        );
    }
}
