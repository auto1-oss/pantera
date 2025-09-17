/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.headers;

import com.artipie.http.Headers;
import com.artipie.http.auth.AuthzSlice;
import com.artipie.scheduling.ArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Login header.
 */
public final class Login extends Header {

    /**
     * Prefix of the basic authorization header.
     */
    private static final String BASIC_PREFIX = "Basic ";

    /**
     * @param headers Header.
     */
    public Login(final Headers headers) {
        this(resolve(headers));
    }

    /**
     * @param value Header value
     */
    public Login(final String value) {
        super(new Header(AuthzSlice.LOGIN_HDR, value));
    }

    private static String resolve(final Headers headers) {
        return headers.find(AuthzSlice.LOGIN_HDR)
            .stream()
            .findFirst()
            .map(Header::getValue)
            .filter(Login::isMeaningful)
            .orElseGet(
                () -> authorizationUser(headers).orElse(ArtifactEvent.DEF_OWNER)
            );
    }

    private static Optional<String> authorizationUser(final Headers headers) {
        return headers.find("Authorization")
            .stream()
            .findFirst()
            .map(Header::getValue)
            .flatMap(Login::decodeAuthorization);
    }

    private static Optional<String> decodeAuthorization(final String header) {
        if (header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            final String encoded = header.substring(BASIC_PREFIX.length()).trim();
            if (encoded.isEmpty()) {
                return Optional.empty();
            }
            try {
                final byte[] decoded = Base64.getDecoder().decode(encoded);
                final String credentials = new String(decoded, StandardCharsets.UTF_8);
                final int separator = credentials.indexOf(':');
                if (separator >= 0) {
                    return Optional.of(credentials.substring(0, separator));
                }
                if (!credentials.isBlank()) {
                    return Optional.of(credentials);
                }
            } catch (final IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean isMeaningful(final String value) {
        return !value.isBlank() && !ArtifactEvent.DEF_OWNER.equals(value);
    }
}
