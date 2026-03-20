/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.model.NpmToken;
import com.auto1.pantera.npm.repository.TokenRepository;
import com.auto1.pantera.npm.security.TokenGenerator;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * NPM add user slice integrated with Pantera authentication.
 * Authenticates users against Pantera (Keycloak) and generates NPM tokens.
 * 
 * @since 1.1
 */
public final class PanteraAddUserSlice implements Slice {
    
    /**
     * URL pattern for user creation.
     */
    public static final Pattern PATTERN = Pattern.compile(
        "^.*/-/user/org\\.couchdb\\.user:(.+)$"
    );
    
    /**
     * Pantera authentication.
     */
    private final Authentication auth;
    
    /**
     * Token repository.
     */
    private final TokenRepository tokens;
    
    /**
     * Token generator.
     */
    private final TokenGenerator tokenGen;
    
    /**
     * Constructor.
     * @param auth Pantera authentication
     * @param tokens Token repository
     * @param tokenGen Token generator
     */
    public PanteraAddUserSlice(
        final Authentication auth,
        final TokenRepository tokens,
        final TokenGenerator tokenGen
    ) {
        this.auth = auth;
        this.tokens = tokens;
        this.tokenGen = tokenGen;
    }
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final Matcher matcher = PATTERN.matcher(line.uri().getPath());
        if (!matcher.matches()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.badRequest()
                    .textBody("Invalid user path")
                    .build()
            );
        }
        
        final String username = matcher.group(1);
        
        return body.asBytesFuture()
            .thenCompose(bytes -> {
                final JsonObject json = Json.createReader(
                    new StringReader(new String(bytes, StandardCharsets.UTF_8))
                ).readObject();
                
                final String password = json.getString("password", "");
                
                if (password.isEmpty()) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.badRequest()
                            .jsonBody(Json.createObjectBuilder()
                                .add("error", "Password required")
                                .build())
                            .build()
                    );
                }
                
                // Authenticate against Pantera
                return this.authenticateUser(username, password)
                    .thenCompose(authUser -> {
                        if (authUser == null) {
                            return CompletableFuture.completedFuture(
                                ResponseBuilder.unauthorized()
                                    .jsonBody(Json.createObjectBuilder()
                                        .add("error", "Invalid credentials. Use your Pantera username and password.")
                                        .build())
                                    .build()
                            );
                        }
                        
                        // Generate NPM token for Pantera user
                        return this.tokenGen.generate(authUser.name())
                            .thenCompose(token -> this.tokens.save(token)
                                .thenApply(saved -> this.successResponse(authUser.name(), saved))
                            );
                    });
            })
            .exceptionally(err -> {
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                return ResponseBuilder.internalError()
                    .jsonBody(Json.createObjectBuilder()
                        .add("error", cause.getMessage())
                        .build())
                    .build();
            });
    }
    
    /**
     * Authenticate user against Pantera.
     * @param username Username
     * @param password Password
     * @return Future with AuthUser or null if invalid
     */
    private CompletableFuture<AuthUser> authenticateUser(
        final String username,
        final String password
    ) {
        return CompletableFuture.supplyAsync(() -> 
            this.auth.user(username, password).orElse(null)
        );
    }
    
    /**
     * Create success response with token.
     * @param username Username
     * @param token NPM token
     * @return Response
     */
    private Response successResponse(final String username, final NpmToken token) {
        // npm v11+ requires the token in BOTH locations for proper credential storage
        // See: https://github.com/npm/cli/issues/7206
        return ResponseBuilder.created()
            .header("npm-auth-token", token.token())  // Header for npm v11+
            .jsonBody(Json.createObjectBuilder()
                .add("ok", true)
                .add("id", String.format("org.couchdb.user:%s", username))
                .add("rev", "1-" + System.currentTimeMillis())
                .add("token", token.token())  // Body for npm v10 and below
                .build())
            .build();
    }
}
