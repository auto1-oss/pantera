/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.auth;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Optional;
import org.keycloak.authorization.client.AuthzClient;
import org.keycloak.authorization.client.Configuration;
import org.slf4j.MDC;

/**
 * Authentication based on keycloak.
 * @since 0.28.0
 */
public final class AuthFromKeycloak implements Authentication {
    /**
     * Configuration.
     */
    private final Configuration config;

    /**
     * Ctor.
     * @param config Configuration
     */
    public AuthFromKeycloak(final Configuration config) {
        this.config = config;
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Optional<AuthUser> user(final String username, final String password) {
        final AuthzClient client = AuthzClient.create(this.config);
        Optional<AuthUser> res;
        try {
            client.obtainAccessToken(username, password);
            res = Optional.of(new AuthUser(username, "keycloak"));
        } catch (final Throwable err) {
            final EcsLogger logger = EcsLogger.error("com.auto1.pantera.auth")
                .message("Keycloak authentication failed")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(err);
            // Add request context from MDC if available (propagated via TraceContextExecutor)
            final String traceId = MDC.get("trace.id");
            if (traceId != null) {
                logger.field("trace.id", traceId);
            }
            final String clientIp = MDC.get("client.ip");
            if (clientIp != null) {
                logger.field("client.ip", clientIp);
            }
            final String urlPath = MDC.get("url.path");
            if (urlPath != null) {
                logger.field("url.path", urlPath);
            }
            final String repoName = MDC.get("repository.name");
            if (repoName != null) {
                logger.field("repository.name", repoName);
            }
            logger.log();
            res = Optional.empty();
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format("%s()", this.getClass().getSimpleName());
    }
}
