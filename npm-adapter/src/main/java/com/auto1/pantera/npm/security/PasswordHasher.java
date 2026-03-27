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

/**
 * Password hashing interface.
 *
 * @since 1.1
 */
public interface PasswordHasher {
    
    /**
     * Hash a plain password.
     * @param password Plain password
     * @return Hashed password
     */
    String hash(String password);
    
    /**
     * Verify password against hash.
     * @param password Plain password
     * @param hash Stored hash
     * @return True if matches
     */
    boolean verify(String password, String hash);
}
