/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.repository;

import com.artipie.npm.model.User;
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
