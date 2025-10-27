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
import com.artipie.http.headers.Header;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.Tokens;
import com.artipie.http.rq.RequestLine;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

/**
 * NPM login slice that integrates with Artipie OAuth.
 * Validates credentials via Authentication (backed by OAuth) and returns an Artipie JWT token.
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
     * Token service to mint registry tokens.
     */
    private final Tokens tokens;
    
    /**
     * Constructor.
     * @param auth Authentication that validates credentials
     * @param tokens Token service for issuing registry tokens
     */
    public OAuthLoginSlice(final Authentication auth, final Tokens tokens) {
        this.auth = auth;
        this.tokens = tokens;
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
                    final AuthUser authUser = optUser.get();
                    LOGGER.log(Level.INFO, "NPM login successful for: {0}", authUser.name());
                    final String token = createToken(authUser, username, password, headers);
                    return CompletableFuture.completedFuture(
                        successResponse(username, token)
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
     * Create or reuse token for npm login response.
     * @param user Authenticated Artipie user
     * @param username Username provided
     * @param password Password provided
     * @param headers Request headers
     * @return Token string
     */
    private String createToken(
        final AuthUser user,
        final String username,
        final String password,
        final Headers headers
    ) {
        String token = null;
        if (this.tokens != null) {
            try {
                token = this.tokens.generate(user);
            } catch (final Exception err) {
                LOGGER.log(
                    Level.WARNING,
                    "Failed to generate npm token via Tokens service for {0}: {1}",
                    new Object[] { user.name(), err.getMessage() }
                );
            }
        }
        if (token == null || token.isEmpty()) {
            token = extractTokenFromHeaders(headers).orElse(null);
        }
        if ((token == null || token.isEmpty()) && password != null) {
            final String basic = String.format("%s:%s", username, password);
            token = Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
        }
        return token;
    }
    
    /**
     * Extract JWT token from Authorization header.
     * @param headers Request headers
     * @return JWT token or null if not found
     */
    private Optional<String> extractTokenFromHeaders(final Headers headers) {
        return headers.find("authorization").stream()
            .findFirst()
            .map(Header::getValue)
            .filter(v -> v.toLowerCase().startsWith("bearer "))
            .map(v -> v.substring(7).trim())
            .filter(v -> !v.isEmpty());
    }
    
    /**
     * Create successful login response.
     * @param username Username
     * @param token JWT token (from Authorization header)
     * @return JSON response string
     */
    private Response successResponse(final String username, final String token) {
        final ResponseBuilder response = ResponseBuilder.created();
        final javax.json.JsonObjectBuilder json = Json.createObjectBuilder()
            .add("ok", true)
            .add("id", "org.couchdb.user:" + username)
            .add("rev", "1-" + System.currentTimeMillis());
        
        // Include token if available (npm CLI needs this to store in ~/.npmrc)
        if (token != null && !token.isEmpty()) {
            json.add("token", token);
            response.header("npm-auth-token", token);
        }
        
        return response.jsonBody(json.build()).build();
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
