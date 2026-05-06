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

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Optional;
import org.keycloak.authorization.client.AuthzClient;
import org.keycloak.authorization.client.Configuration;
import org.keycloak.authorization.client.util.HttpResponseException;
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
    public Optional<AuthUser> user(final String username, final String password) {
        final AuthzClient client = AuthzClient.create(this.config);
        Optional<AuthUser> res;
        try {
            client.obtainAccessToken(username, password);
            res = Optional.of(new AuthUser(username, "keycloak"));
        } catch (final HttpResponseException err) {
            // NOTE: client.ip, user.name, trace.id are in MDC (set by EcsLoggingSlice).
            // EcsLayout includes all MDC entries — do NOT add them here to avoid duplicates.
            final int status = err.getStatusCode();
            final boolean isCredentialFailure = status == 401 || status == 403;
            final EcsLogger logger = isCredentialFailure
                ? EcsLogger.warn("com.auto1.pantera.auth")
                : EcsLogger.error("com.auto1.pantera.auth");
            logger.message("Keycloak authentication failed for user '" + username + "'")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("http.response.status_code", status)
                .error(err);
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
        } catch (final Throwable err) {
            // System failures (network, timeout, Keycloak down) — alert-worthy.
            // NOTE: client.ip, user.name, trace.id are in MDC (set by EcsLoggingSlice).
            final EcsLogger logger = EcsLogger.error("com.auto1.pantera.auth")
                .message("Keycloak authentication failed for user '" + username + "'")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .error(err);
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
