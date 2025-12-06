/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.auth;

import com.artipie.api.AuthTokenRest;
import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.Authentication;
import com.artipie.http.log.EcsLogger;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Authentication that treats JWT tokens as passwords.
 * <p>
 * This allows clients to use their JWT token (obtained via /api/v1/oauth/token)
 * as the password in Basic Authentication headers. The JWT is validated locally
 * using the shared secret without any external IdP calls.
 * </p>
 * <p>
 * Usage in Maven settings.xml:
 * <pre>
 * &lt;server&gt;
 *   &lt;id&gt;artipie&lt;/id&gt;
 *   &lt;username&gt;user@example.com&lt;/username&gt;
 *   &lt;password&gt;eyJhbGciOiJIUzI1NiIs...&lt;/password&gt;
 * &lt;/server&gt;
 * </pre>
 * </p>
 * <p>
 * This approach follows the same pattern used by JFrog Artifactory (Access Tokens),
 * Sonatype Nexus (User Tokens), and GitHub/GitLab (Personal Access Tokens).
 * </p>
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

    /**
     * Create JwtPasswordAuth from JWT secret.
     *
     * @param vertx Vertx instance
     * @param secret JWT secret key
     * @return JwtPasswordAuth instance
     */
    public static JwtPasswordAuth fromSecret(final Vertx vertx, final String secret) {
        final JWTAuth auth = JWTAuth.create(
            vertx,
            new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions().setAlgorithm("HS256").setBuffer(secret)
            )
        );
        return new JwtPasswordAuth(auth);
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        // Quick check: is password a JWT? (starts with "eyJ" and has 2 dots)
        if (!looksLikeJwt(password)) {
            return Optional.empty();
        }
        try {
            // Validate JWT locally using shared secret
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
                EcsLogger.warn("com.artipie.auth")
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
                EcsLogger.warn("com.artipie.auth")
                    .message("JWT token subject does not match provided username")
                    .eventCategory("authentication")
                    .eventAction("jwt_password_auth")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .field("token.subject", tokenSubject)
                    .log();
                return Optional.empty();
            }
            // Extract auth context if present
            final String context = principal.getString(AuthTokenRest.CONTEXT, "jwt-password");
            return Optional.of(new AuthUser(tokenSubject, context));
        } catch (final java.util.concurrent.TimeoutException timeout) {
            EcsLogger.warn("com.artipie.auth")
                .message("JWT validation timed out")
                .eventCategory("authentication")
                .eventAction("jwt_password_auth")
                .eventOutcome("failure")
                .field("user.name", username)
                .log();
            return Optional.empty();
        } catch (final Exception ex) {
            // Invalid JWT - this is expected for non-JWT passwords
            // Don't log - not an error, just means password isn't a JWT
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
