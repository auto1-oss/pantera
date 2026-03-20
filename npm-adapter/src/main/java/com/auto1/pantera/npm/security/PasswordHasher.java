/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
