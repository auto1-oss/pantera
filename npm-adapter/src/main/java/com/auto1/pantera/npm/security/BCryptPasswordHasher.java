/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.security;

import org.mindrot.jbcrypt.BCrypt;

/**
 * BCrypt-based password hasher.
 * Uses work factor of 10 for secure but reasonable performance.
 *
 * @since 1.1
 */
public final class BCryptPasswordHasher implements PasswordHasher {
    
    /**
     * BCrypt work factor.
     */
    private static final int WORK_FACTOR = 10;
    
    @Override
    public String hash(final String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(WORK_FACTOR));
    }
    
    @Override
    public boolean verify(final String password, final String hash) {
        try {
            return BCrypt.checkpw(password, hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
