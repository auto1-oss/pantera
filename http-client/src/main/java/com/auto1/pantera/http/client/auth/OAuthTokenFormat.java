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
        // OAuth Registry V2 error envelope: when the upstream refuses to
        // mint a token (anonymous access denied, repo not found, scope
        // not granted, etc.) it can reply HTTP 200 with a body shaped as
        // {"errors":[{"code":"...","message":"..."}]} — see
        // https://distribution.github.io/distribution/spec/api/#errors-2.
        // GCR (Google Artifact Registry) and DHI do this. Surfacing
        // {@link IllegalStateException} here would mask the real reason
        // and treat a normal denial as a programming error; instead we
        // throw {@link UpstreamAuthDeniedException} carrying the upstream
        // code so the upper layer can distinguish "the upstream said no"
        // from "the response shape is broken".
        if (json.containsKey("errors")) {
            throw new UpstreamAuthDeniedException(json.get("errors").toString());
        }
        throw new IllegalStateException(
            "Token response contains neither 'access_token' nor 'token' field"
        );
    }
}
