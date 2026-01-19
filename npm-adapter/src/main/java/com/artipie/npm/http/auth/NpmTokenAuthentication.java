/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.auth;

import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.TokenAuthentication;
import com.artipie.npm.repository.TokenRepository;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * NPM token authentication.
 * Validates NPM tokens from StorageTokenRepository.
 * 
 * @since 1.1
 */
public final class NpmTokenAuthentication implements TokenAuthentication {
    
    /**
     * Token repository.
     */
    private final TokenRepository tokens;
    
    /**
     * Fallback token authentication (for Artipie JWT tokens).
     */
    private final TokenAuthentication fallback;
    
    /**
     * Constructor with fallback.
     * @param tokens Token repository
     * @param fallback Fallback authentication (for JWT tokens)
     */
    public NpmTokenAuthentication(
        final TokenRepository tokens,
        final TokenAuthentication fallback
    ) {
        this.tokens = tokens;
        this.fallback = fallback;
    }
    
    /**
     * Constructor without fallback.
     * @param tokens Token repository
     */
    public NpmTokenAuthentication(final TokenRepository tokens) {
        this(tokens, tkn -> CompletableFuture.completedFuture(Optional.empty()));
    }
    
    @Override
    public CompletionStage<Optional<AuthUser>> user(final String token) {
        // First, try to validate as NPM token
        return this.tokens.findByToken(token)
            .thenCompose(optToken -> {
                if (optToken.isPresent()) {
                    // Valid NPM token found
                    return CompletableFuture.completedFuture(
                        Optional.of(new AuthUser(optToken.get().username(), "npm"))
                    );
                }
                // Not an NPM token, try fallback (Artipie JWT)
                return this.fallback.user(token);
            })
            .exceptionally(err -> {
                // On error, return empty (unauthorized)
                return Optional.empty();
            });
    }
}
