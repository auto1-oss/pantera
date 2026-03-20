/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable NPM authentication token.
 *
 * @since 1.1
 */
public final class NpmToken {
    
    /**
     * Token ID.
     */
    private final String id;
    
    /**
     * Token value.
     */
    private final String token;
    
    /**
     * Owner username.
     */
    private final String username;
    
    /**
     * Creation time.
     */
    private final Instant createdAt;
    
    /**
     * Expiration time (optional).
     */
    private final Instant expiresAt;
    
    /**
     * Constructor.
     * @param token Token value
     * @param username Owner username
     * @param expiresAt Expiration time (null for no expiration)
     */
    public NpmToken(final String token, final String username, final Instant expiresAt) {
        this(
            java.util.UUID.randomUUID().toString(),
            token,
            username,
            Instant.now(),
            expiresAt
        );
    }
    
    /**
     * Full constructor.
     * @param id Token ID
     * @param token Token value
     * @param username Owner username
     * @param createdAt Creation time
     * @param expiresAt Expiration time
     */
    public NpmToken(
        final String id,
        final String token,
        final String username,
        final Instant createdAt,
        final Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.token = Objects.requireNonNull(token, "token cannot be null");
        this.username = Objects.requireNonNull(username, "username cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.expiresAt = expiresAt; // Can be null
    }
    
    /**
     * Check if token is expired.
     * @return True if expired
     */
    public boolean isExpired() {
        return this.expiresAt != null && Instant.now().isAfter(this.expiresAt);
    }
    
    /**
     * Get token ID.
     * @return Token ID
     */
    public String id() {
        return this.id;
    }
    
    /**
     * Get token value.
     * @return Token value
     */
    public String token() {
        return this.token;
    }
    
    /**
     * Get owner username.
     * @return Username
     */
    public String username() {
        return this.username;
    }
    
    /**
     * Get creation time.
     * @return Creation time
     */
    public Instant createdAt() {
        return this.createdAt;
    }
    
    /**
     * Get expiration time.
     * @return Expiration time or null
     */
    public Instant expiresAt() {
        return this.expiresAt;
    }
}
