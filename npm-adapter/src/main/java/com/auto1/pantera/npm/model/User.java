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
package com.auto1.pantera.npm.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable NPM user model.
 * Represents a user registered via npm adduser/login.
 *
 * @since 1.1
 */
public final class User {
    
    /**
     * User ID.
     */
    private final String id;
    
    /**
     * Username.
     */
    private final String username;
    
    /**
     * Password hash (BCrypt).
     */
    private final String passwordHash;
    
    /**
     * Email address.
     */
    private final String email;
    
    /**
     * Creation timestamp.
     */
    private final Instant createdAt;
    
    /**
     * Constructor for new user.
     * @param username Username
     * @param passwordHash Password hash
     * @param email Email
     */
    public User(final String username, final String passwordHash, final String email) {
        this(
            java.util.UUID.randomUUID().toString(),
            username,
            passwordHash,
            email,
            Instant.now()
        );
    }
    
    /**
     * Full constructor (for loading from storage).
     * @param id User ID
     * @param username Username
     * @param passwordHash Password hash
     * @param email Email
     * @param createdAt Creation time
     */
    public User(
        final String id,
        final String username,
        final String passwordHash,
        final String email,
        final Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.username = Objects.requireNonNull(username, "username cannot be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash cannot be null");
        this.email = Objects.requireNonNull(email, "email cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }
    
    /**
     * Get user ID.
     * @return User ID
     */
    public String id() {
        return this.id;
    }
    
    /**
     * Get username.
     * @return Username
     */
    public String username() {
        return this.username;
    }
    
    /**
     * Get password hash.
     * @return Password hash
     */
    public String passwordHash() {
        return this.passwordHash;
    }
    
    /**
     * Get email.
     * @return Email
     */
    public String email() {
        return this.email;
    }
    
    /**
     * Get creation time.
     * @return Creation timestamp
     */
    public Instant createdAt() {
        return this.createdAt;
    }
    
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final User user = (User) other;
        return this.id.equals(user.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(this.id);
    }
}
