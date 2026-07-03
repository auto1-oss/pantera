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
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NPM .npmrc auth endpoint that generates configuration for users.
 * Supports two endpoints (with .auth prefix to avoid package name conflicts):
 * - /.auth - generates global registry config
 * - /.auth/{scope} - generates scoped registry config
 * 
 * The .auth prefix avoids conflicts with package names that contain 'auth'
 * (e.g., @admin/auth, oauth-client) since NPM package names cannot start with a dot.
 * 
 * Uses Keycloak JWT tokens instead of local NPM tokens:
 * - Basic Auth: Authenticates with Keycloak and returns JWT as NPM token
 * - Bearer token: Reuses existing JWT token
 * 
 * Returns .npmrc format:
 * <pre>
 * registry=https://repo.url
 * //repo.url/:_authToken=jwt-token-from-keycloak
 * //repo.url/:username=user
 * //repo.url/:email=user@example.com
 * //repo.url/:always-auth=true
 * </pre>
 * 
 * @since 1.18.18
 */
public final class NpmrcAuthSlice implements Slice {

    /**
     * Pattern for /.auth endpoint (dot prefix to avoid package conflicts).
     * Package names cannot start with a dot, so this avoids conflicts with
     * packages like @admin/auth, oauth-client, etc.
     */
    public static final Pattern AUTH_PATTERN = Pattern.compile("^.*/\\.auth/?$");

    /**
     * Pattern for /.auth/{scope} endpoint (dot prefix to avoid package conflicts).
     * Accepts scope with or without @ prefix.
     * Package names cannot start with a dot, ensuring no conflicts.
     */
    public static final Pattern AUTH_SCOPE_PATTERN = Pattern.compile("^.*/\\.auth/(@?[^/]+)/?$");

    /**
     * Repository base URL.
     */
    private final URL baseUrl;

    /**
     * Pantera authentication.
     */
    private final Authentication auth;

    /**
     * Token service to generate JWT tokens.
     */
    private final Tokens tokens;

    /**
     * Token authentication (for Bearer tokens).
     */
    private final TokenAuthentication tokenAuth;

    /**
     * Default email domain.
     */
    private static final String DEFAULT_EMAIL_DOMAIN = "pantera.local";

    /**
     * Constructor.
     * @param baseUrl Repository base URL
     * @param auth Pantera authentication
     * @param tokens Token service to generate JWT tokens
     * @param tokenAuth Token authentication for Bearer tokens
     */
    public NpmrcAuthSlice(
        final URL baseUrl,
        final Authentication auth,
        final Tokens tokens,
        final TokenAuthentication tokenAuth
    ) {
        this.baseUrl = baseUrl;
        this.auth = auth;
        this.tokens = tokens;
        this.tokenAuth = tokenAuth;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        
        // Check for scoped auth
        final Matcher scopeMatcher = AUTH_SCOPE_PATTERN.matcher(path);
        final Optional<String> scope = scopeMatcher.matches() 
            ? Optional.of(scopeMatcher.group(1))
            : Optional.empty();

        // Extract and authenticate user
        return this.extractUserAndToken(headers)
            .thenCompose(result -> {
                if (result.user.isEmpty()) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.unauthorized()
                            .header("WWW-Authenticate", "Basic realm=\"Pantera NPM Registry\"")
                            .textBody("Authentication required")
                            .build()
                    );
                }

                final AuthUser user = result.user.get();
                
                // If Bearer token provided, reuse it; otherwise generate JWT token
                if (result.token != null) {
                    return CompletableFuture.completedFuture(
                        this.generateNpmrc(user, result.token, scope)
                    );
                }
                
                // Generate JWT token (same as npm login via OAuthLoginSlice)
                try {
                    final String jwtToken = this.tokens.generate(user);
                    return CompletableFuture.completedFuture(
                        this.generateNpmrc(user, jwtToken, scope)
                    );
                } catch (Exception err) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.internalError()
                            .textBody("Failed to generate JWT token: " + err.getMessage())
                            .build()
                    );
                }
            })
            .exceptionally(err -> {
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                return ResponseBuilder.internalError()
                    .textBody("Error generating auth config: " + cause.getMessage())
                    .build();
            });
    }

    /**
     * Extract authenticated user and token from request headers.
     * @param headers Request headers
     * @return Future with UserTokenResult
     */
    private CompletableFuture<UserTokenResult> extractUserAndToken(final Headers headers) {
        final Optional<String> authHeader = headers.stream()
            .filter(h -> "Authorization".equalsIgnoreCase(h.getKey()))
            .map(h -> h.getValue())
            .findFirst();

        if (authHeader.isEmpty()) {
            return CompletableFuture.completedFuture(new UserTokenResult(Optional.empty(), null));
        }

        final String authValue = authHeader.get();
        
        // Handle Basic auth - authenticate and generate new token
        if (authValue.startsWith("Basic ")) {
            final String encoded = authValue.substring(6);
            final String decoded = new String(
                Base64.getDecoder().decode(encoded),
                StandardCharsets.UTF_8
            );
            final int colon = decoded.indexOf(':');
            if (colon > 0) {
                final String username = decoded.substring(0, colon);
                final String password = decoded.substring(colon + 1);
                final Optional<AuthUser> user = this.auth.user(username, password);
                return CompletableFuture.completedFuture(new UserTokenResult(user, null));
            }
        }
        
        // Handle Bearer token - validate and reuse existing token
        if (authValue.startsWith("Bearer ")) {
            final String token = authValue.substring(7).trim();
            return this.tokenAuth.user(token)
                .thenApply(user -> new UserTokenResult(user, token))
                .toCompletableFuture();
        }

        return CompletableFuture.completedFuture(new UserTokenResult(Optional.empty(), null));
    }

    /**
     * Result of user and token extraction.
     */
    private static final class UserTokenResult {
        private final Optional<AuthUser> user;
        private final String token;

        UserTokenResult(final Optional<AuthUser> user, final String token) {
            this.user = user;
            this.token = token;
        }
    }

    /**
     * Generate .npmrc configuration response.
     * @param user Authenticated user
     * @param token Generated NPM token
     * @param scope Optional scope
     * @return Response with .npmrc content
     */
    private Response generateNpmrc(
        final AuthUser user,
        final String token,
        final Optional<String> scope
    ) {
        final String registryUrl = this.baseUrl.toString().replaceAll("/$", "");
        final String registryHost = this.baseUrl.getHost();
        final String port = this.baseUrl.getPort() > 0 && this.baseUrl.getPort() != 80 && this.baseUrl.getPort() != 443
            ? ":" + this.baseUrl.getPort()
            : "";
        // Auth base uses only host:port, not the full path
        final String registryAuthBase = "//" + registryHost + port;
        
        // AuthUser doesn't have email, use default format
        final String email = user.name() + "@" + DEFAULT_EMAIL_DOMAIN;

        final StringBuilder npmrc = new StringBuilder(256);
        
        if (scope.isPresent()) {
            // Scoped configuration - add @ prefix if not present
            final String scopeName = scope.get().startsWith("@") ? scope.get() : "@" + scope.get();
            npmrc.append(scopeName).append(":registry=").append(registryUrl).append("\n");
        } else {
            // Global configuration
            npmrc.append("registry=").append(registryUrl).append("\n");
        }
        
        npmrc.append(registryAuthBase).append("/:_authToken=").append(token).append("\n");
        npmrc.append(registryAuthBase).append("/:username=").append(user.name()).append("\n");
        npmrc.append(registryAuthBase).append("/:email=").append(email).append("\n");
        npmrc.append(registryAuthBase).append("/:always-auth=true\n");

        return ResponseBuilder.ok()
            .header("Content-Type", "text/plain; charset=utf-8")
            .body(npmrc.toString().getBytes(StandardCharsets.UTF_8))
            .build();
    }
}
