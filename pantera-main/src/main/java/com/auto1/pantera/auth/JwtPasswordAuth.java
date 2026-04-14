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

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Authentication that treats JWT tokens as passwords.
 *
 * <p>Lets clients use an API token (obtained via {@code POST /api/v1/auth/token})
 * as the password in a Basic Authentication header. The JWT is verified locally
 * against the RS256 public key ({@code meta.jwt.public-key-path}) — no external
 * IdP round trip.
 *
 * <p>Usage in {@code settings.xml}:
 * <pre>
 * &lt;server&gt;
 *   &lt;id&gt;pantera&lt;/id&gt;
 *   &lt;username&gt;user@example.com&lt;/username&gt;
 *   &lt;password&gt;eyJhbGciOiJSUzI1NiIs...&lt;/password&gt;
 * &lt;/server&gt;
 * </pre>
 *
 * <p>Same pattern as JFrog Artifactory Access Tokens, Sonatype Nexus User Tokens,
 * and GitHub/GitLab Personal Access Tokens.
 *
 * @since 1.20.7
 */
public final class JwtPasswordAuth implements Authentication {

    /**
     * JWT authentication provider for local validation.
     */
    private final JWTAuth jwtAuth;

    /**
     * Whether to require username match with token subject.
     */
    private final boolean requireUsernameMatch;

    /**
     * Ctor with username matching enabled.
     *
     * @param jwtAuth JWT authentication provider
     */
    public JwtPasswordAuth(final JWTAuth jwtAuth) {
        this(jwtAuth, true);
    }

    /**
     * Ctor.
     *
     * @param jwtAuth JWT authentication provider
     * @param requireUsernameMatch Whether to require username to match token subject
     */
    public JwtPasswordAuth(final JWTAuth jwtAuth, final boolean requireUsernameMatch) {
        this.jwtAuth = jwtAuth;
        this.requireUsernameMatch = requireUsernameMatch;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        // Quick check: is password a JWT? (starts with "eyJ" and has 2 dots)
        if (!looksLikeJwt(password)) {
            return Optional.empty();
        }
        try {
            // Validate JWT locally against the RS256 public key
            final CompletableFuture<User> future = this.jwtAuth
                .authenticate(new TokenCredentials(password))
                .toCompletionStage()
                .toCompletableFuture();
            // Use short timeout to avoid blocking on invalid tokens
            final User verified = future.get(500, TimeUnit.MILLISECONDS);
            final JsonObject principal = verified.principal();
            // Extract subject from token
            final String tokenSubject = principal.getString(AuthTokenRest.SUB);
            if (tokenSubject == null || tokenSubject.isEmpty()) {
                EcsLogger.warn("com.auto1.pantera.auth")
                    .message("JWT token missing 'sub' claim")
                    .eventCategory("authentication")
                    .eventAction("jwt_password_auth")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .log();
                return Optional.empty();
            }
            // Security: Verify username matches token subject if required
            if (this.requireUsernameMatch && !username.equals(tokenSubject)) {
                EcsLogger.warn("com.auto1.pantera.auth")
                    .message(String.format("JWT token subject does not match provided username (subject=%s)", tokenSubject))
                    .eventCategory("authentication")
                    .eventAction("jwt_password_auth")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .log();
                return Optional.empty();
            }
            // Extract auth context if present
            final String context = principal.getString(AuthTokenRest.CONTEXT, "jwt-password");
            return Optional.of(new AuthUser(tokenSubject, context));
        } catch (final java.util.concurrent.TimeoutException timeout) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("JWT validation timed out")
                .eventCategory("authentication")
                .eventAction("jwt_password_auth")
                .eventOutcome("failure")
                .field("user.name", username)
                .log();
            return Optional.empty();
        } catch (final Exception ex) {
            // Password survived looksLikeJwt() — it has the shape of a JWT — so
            // a verification error here is almost always diagnosable (wrong
            // signature, wrong algorithm, mismatched key, expired, missing
            // required claim). Log the cause at DEBUG so operators can
            // investigate without spamming INFO on every non-JWT password in
            // the provider chain.
            EcsLogger.debug("com.auto1.pantera.auth")
                .message("JWT-as-password verification failed")
                .eventCategory("authentication")
                .eventAction("jwt_password_auth")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("error.message",
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName())
                .log();
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return String.format(
            "%s(requireUsernameMatch=%s)",
            this.getClass().getSimpleName(),
            this.requireUsernameMatch
        );
    }

    /**
     * Check if password looks like a JWT token.
     * JWTs are Base64URL encoded and have format: header.payload.signature
     *
     * @param password Password to check
     * @return True if password looks like a JWT
     */
    private static boolean looksLikeJwt(final String password) {
        if (password == null || password.length() < 20) {
            return false;
        }
        // JWTs start with "eyJ" (Base64 for '{"')
        if (!password.startsWith("eyJ")) {
            return false;
        }
        // JWTs have exactly 2 dots separating 3 parts
        int dots = 0;
        for (int i = 0; i < password.length(); i++) {
            if (password.charAt(i) == '.') {
                dots++;
            }
        }
        return dots == 2;
    }
}
