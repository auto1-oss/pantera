/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.rq.RequestLine;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

                EcsLogger.debug("com.auto1.pantera.npm")
                    .message("NPM login attempt")
                    .eventCategory("authentication")
                    .eventAction("login")
                    .field("user.name", username)
                    .log();

                // Validate credentials via Authentication (synchronous)
                final java.util.Optional<com.auto1.pantera.http.auth.AuthUser> optUser =
                    this.auth.user(username, password);

                if (optUser.isPresent()) {
                    // Authentication successful
                    final AuthUser authUser = optUser.get();
                    EcsLogger.info("com.auto1.pantera.npm")
                        .message("NPM login successful")
                        .eventCategory("authentication")
                        .eventAction("login")
                        .eventOutcome("success")
                        .field("user.name", authUser.name())
                        .log();
                    final String token = createToken(authUser, username, password, headers);
                    return CompletableFuture.completedFuture(
                        successResponse(username, token)
                    );
                }

                EcsLogger.warn("com.auto1.pantera.npm")
                    .message("NPM login failed")
                    .eventCategory("authentication")
                    .eventAction("login")
                    .eventOutcome("failure")
                    .field("user.name", username)
                    .log();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.unauthorized()
                        .jsonBody("{\"error\": \"Invalid credentials\"}")
                        .build()
                );

            } catch (Exception e) {
                EcsLogger.error("com.auto1.pantera.npm")
                    .message("NPM login error")
                    .eventCategory("authentication")
                    .eventAction("login")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
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
                EcsLogger.warn("com.auto1.pantera.npm")
                    .message("Failed to generate npm token via Tokens service")
                    .eventCategory("authentication")
                    .eventAction("token_generation")
                    .eventOutcome("failure")
                    .field("user.name", user.name())
                    .error(err)
                    .log();
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
