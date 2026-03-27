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
package com.auto1.pantera.npm.repository;

import com.auto1.pantera.npm.model.User;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * User repository interface.
 * Handles user persistence and authentication.
 *
 * @since 1.1
 */
public interface UserRepository {
    
    /**
     * Check if user exists.
     * @param username Username
     * @return Future with true if exists
     */
    CompletableFuture<Boolean> exists(String username);
    
    /**
     * Save user.
     * @param user User to save
     * @return Future with saved user
     */
    CompletableFuture<User> save(User user);
    
    /**
     * Find user by username.
     * @param username Username
     * @return Future with optional user
     */
    CompletableFuture<Optional<User>> findByUsername(String username);
    
    /**
     * Authenticate user.
     * @param username Username
     * @param password Plain password
     * @return Future with optional user if authentication succeeds
     */
    CompletableFuture<Optional<User>> authenticate(String username, String password);
}
