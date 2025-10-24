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
import com.artipie.http.rq.RequestLine;



import com.artipie.npm.model.NpmToken;
import com.artipie.npm.model.User;
import com.artipie.npm.repository.TokenRepository;
import com.artipie.npm.repository.UserRepository;
import com.artipie.npm.security.PasswordHasher;
import com.artipie.npm.security.TokenGenerator;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Add user slice - handles npm adduser command.
 * Endpoint: PUT /-/user/org.couchdb.user:{username}
 *
 * @since 1.1
 */
public final class AddUserSlice implements Slice {
    
    /**
     * URL pattern for user creation.
     */
    public static final Pattern PATTERN = Pattern.compile(
        "^/-/user/org\\.couchdb\\.user:(.+)$"
    );
    
    /**
     * User repository.
     */
    private final UserRepository users;
    
    /**
     * Token repository.
     */
    private final TokenRepository tokens;
    
    /**
     * Password hasher.
     */
    private final PasswordHasher hasher;
    
    /**
     * Token generator.
     */
    private final TokenGenerator tokenGen;
    
    /**
     * Constructor.
     * @param users User repository
     * @param tokens Token repository
     * @param hasher Password hasher
     * @param tokenGen Token generator
     */
    public AddUserSlice(
        final UserRepository users,
        final TokenRepository tokens,
        final PasswordHasher hasher,
        final TokenGenerator tokenGen
    ) {
        this.users = users;
        this.tokens = tokens;
        this.hasher = hasher;
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
                
                return this.createUser(username, json);
            })
            .thenCompose(user -> this.tokenGen.generate(user)
                .thenCompose(token -> this.tokens.save(token)
                    .thenApply(saved -> this.successResponse(user, saved))
                )
            )
            .exceptionally(err -> {
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (cause instanceof UserExistsException) {
                    return ResponseBuilder.badRequest()
                        .jsonBody(Json.createObjectBuilder()
                            .add("error", "User already exists")
                            .build())
                        .build();
                }
                return ResponseBuilder.internalError()
                    .jsonBody(Json.createObjectBuilder()
                        .add("error", cause.getMessage())
                        .build())
                    .build();
            });
    }
    
    /**
     * Create user.
     * @param username Username from URL
     * @param json Request body
     * @return Future with created user
     */
    private CompletableFuture<User> createUser(final String username, final JsonObject json) {
        final String password = json.getString("password", "");
        final String email = json.getString("email", "");
        
        if (password.isEmpty() || email.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Password and email required")
            );
        }
        
        return this.users.exists(username)
            .thenCompose(exists -> {
                if (exists) {
                    return CompletableFuture.failedFuture(
                        new UserExistsException(username)
                    );
                }
                final String hashed = this.hasher.hash(password);
                return this.users.save(new User(username, hashed, email));
            });
    }
    
    /**
     * Build success response.
     * @param user Created user
     * @param token Generated token
     * @return Response
     */
    private Response successResponse(final User user, final NpmToken token) {
        // npm v11+ requires the token in BOTH locations for proper credential storage
        // See: https://github.com/npm/cli/issues/7206
        return ResponseBuilder.created()
            .header("npm-auth-token", token.token())  // Header for npm v11+
            .jsonBody(Json.createObjectBuilder()
                .add("ok", true)
                .add("id", "org.couchdb.user:" + user.username())
                .add("rev", "1-" + user.id())
                .add("token", token.token())  // Body for npm v10 and below
                .build())
            .build();
    }
    
    /**
     * User exists exception.
     */
    public static final class UserExistsException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        
        /**
         * Constructor.
         * @param username Username
         */
        public UserExistsException(final String username) {
            super("User already exists: " + username);
        }
    }
}
