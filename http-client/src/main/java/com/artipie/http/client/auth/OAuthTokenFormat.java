/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.auth;

import java.io.ByteArrayInputStream;
import javax.json.JsonObject;
import javax.json.Json;

/**
 * Authentication token response.
 * See <a href="https://tools.ietf.org/html/rfc6750#section-4">Example Access Token Response</a>
 * <p>
 * Docker registries may return the token in either "access_token" (RFC 6750) or
 * "token" (Docker-specific) field. This implementation handles both formats.
 *
 * @since 0.5
 */
final class OAuthTokenFormat implements TokenFormat {

    @Override
    public String token(final byte[] content) {
        final JsonObject json = Json.createReader(new ByteArrayInputStream(content))
            .readObject();
        // Docker registries may return token in either "access_token" (RFC 6750)
        // or "token" (Docker-specific format). Try access_token first, fall back to token.
        if (json.containsKey("access_token")) {
            return json.getString("access_token");
        }
        if (json.containsKey("token")) {
            return json.getString("token");
        }
        throw new IllegalStateException(
            "Token response contains neither 'access_token' nor 'token' field: " + json
        );
    }
}
