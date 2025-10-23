/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.auth;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.auth.Authentication;
import com.artipie.http.rq.RequestLine;
import java.io.StringReader;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

/**
 * NPM login slice that integrates with Artipie OAuth.
 * Validates credentials via Authentication (backed by OAuth) and returns JWT token.
 * Does NOT create npm-specific tokens - only uses global Keycloak JWT.
 * 
 * @since 1.2
 */
public final class OAuthLoginSlice implements Slice {
    
    private static final Logger LOGGER = Logger.getLogger(OAuthLoginSlice.class.getName());
    
    /**
     * Authentication to validate credentials.
     * In Artipie, this should be connected to the system that can return JWT tokens.
     */
    private final Authentication auth;
    
    /**
     * Constructor.
     * @param auth Authentication that validates credentials
     */
    public OAuthLoginSlice(final Authentication auth) {
        this.auth = auth;
    }
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return body.asStringFuture().thenCompose(bodyStr -> {
            try {
                final JsonObject json = parseJson(bodyStr);
                final String username = json.getString("name");
                final String password = json.getString("password");
                
                LOGGER.log(Level.INFO, "NPM login attempt for user: {0}", username);
                
                // Validate credentials via Authentication (synchronous)
                final java.util.Optional<com.artipie.http.auth.AuthUser> optUser = 
                    this.auth.user(username, password);
                
                if (optUser.isPresent()) {
                    // Authentication successful
                    LOGGER.log(Level.INFO, "NPM login successful for: {0}", username);
                    
                    // Extract JWT token from Authorization header if present
                    // npm CLI sends the JWT with the login request
                    final String token = extractTokenFromHeaders(headers);
                    
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok()
                            .jsonBody(createSuccessResponse(username, token))
                            .build()
                    );
                }
                
                LOGGER.log(Level.WARNING, "NPM login failed for: {0}", username);
                return CompletableFuture.completedFuture(
                    ResponseBuilder.unauthorized()
                        .jsonBody("{\"error\": \"Invalid credentials\"}")
                        .build()
                );
                    
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "NPM login error", e);
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badRequest()
                        .jsonBody("{\"error\": \"Invalid request\"}")
                        .build()
                );
            }
        });
    }
    
    /**
     * Extract JWT token from Authorization header.
     * @param headers Request headers
     * @return JWT token or null if not found
     */
    private String extractTokenFromHeaders(final Headers headers) {
        return headers.find("authorization").stream()
            .findFirst()
            .map(h -> h.getValue())
            .filter(v -> v.toLowerCase().startsWith("bearer "))
            .map(v -> v.substring(7).trim())
            .orElse(null);
    }
    
    /**
     * Create successful login response.
     * @param username Username
     * @param token JWT token (from Authorization header)
     * @return JSON response string
     */
    private String createSuccessResponse(final String username, final String token) {
        final javax.json.JsonObjectBuilder builder = Json.createObjectBuilder()
            .add("ok", true)
            .add("id", "org.couchdb.user:" + username)
            .add("rev", "_we_dont_use_revs_any_more");
        
        // Include token if available (npm CLI needs this to store in ~/.npmrc)
        if (token != null && !token.isEmpty()) {
            builder.add("token", token);
        }
        
        return builder.build().toString();
    }
    
    /**
     * Parse JSON from string.
     * @param json JSON string
     * @return JsonObject
     */
    private JsonObject parseJson(final String json) {
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
