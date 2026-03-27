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
package com.auto1.pantera.npm.security;

import com.auto1.pantera.npm.model.NpmToken;
import com.auto1.pantera.npm.model.User;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * NPM authentication token generator.
 *
 * @since 1.1
 */
public final class TokenGenerator {
    
    /**
     * Token length in bytes (before base64 encoding).
     */
    private static final int TOKEN_BYTES = 32;
    
    /**
     * Secure random generator.
     */
    private final SecureRandom random;
    
    /**
     * Default constructor.
     */
    public TokenGenerator() {
        this(new SecureRandom());
    }
    
    /**
     * Constructor with custom random (for testing).
     * @param random Random generator
     */
    public TokenGenerator(final SecureRandom random) {
        this.random = random;
    }
    
    /**
     * Generate token for user.
     * @param user User
     * @return Future with generated token
     */
    public CompletableFuture<NpmToken> generate(final User user) {
        return this.generate(user, null);
    }
    
    /**
     * Generate token with expiration.
     * @param user User
     * @param expiresAt Expiration time (null for no expiration)
     * @return Future with generated token
     */
    public CompletableFuture<NpmToken> generate(final User user, final Instant expiresAt) {
        return this.generate(user.username(), expiresAt);
    }
    
    /**
     * Generate token for username (Pantera integration).
     * @param username Username
     * @return Future with generated token
     */
    public CompletableFuture<NpmToken> generate(final String username) {
        return this.generate(username, null);
    }
    
    /**
     * Generate token for username with expiration (Pantera integration).
     * @param username Username
     * @param expiresAt Expiration time (null for no expiration)
     * @return Future with generated token
     */
    public CompletableFuture<NpmToken> generate(final String username, final Instant expiresAt) {
        final byte[] bytes = new byte[TOKEN_BYTES];
        this.random.nextBytes(bytes);
        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        
        return CompletableFuture.completedFuture(
            new NpmToken(token, username, expiresAt)
        );
    }
}
