/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.slf4j.MDC;

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
        // 1. Try artipie_login header (set by AuthzSlice after successful auth)
        return headers.find(AuthzSlice.LOGIN_HDR)
            .stream()
            .findFirst()
            .map(Header::getValue)
            .filter(Login::isMeaningful)
            .orElseGet(() -> {
                // 2. Try Basic auth header extraction
                final Optional<String> fromAuth = authorizationUser(headers);
                if (fromAuth.isPresent()) {
                    return fromAuth.get();
                }
                // 3. Try MDC (set by AuthzSlice for Bearer/JWT users)
                final String mdcUser = MDC.get("user.name");
                if (mdcUser != null && !mdcUser.isEmpty() && !"anonymous".equals(mdcUser)) {
                    return mdcUser;
                }
                // 4. Default fallback
                return ArtifactEvent.DEF_OWNER;
            });
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
            } catch (final IllegalArgumentException ex) {
                EcsLogger.debug("com.auto1.pantera.http")
                    .message("Failed to decode Basic auth credentials")
                    .error(ex)
                    .log();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean isMeaningful(final String value) {
        return !value.isBlank() && !ArtifactEvent.DEF_OWNER.equals(value);
    }
}
