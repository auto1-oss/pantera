/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.security;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link BCryptPasswordHasher}.
 *
 * @since 1.1
 */
final class BCryptPasswordHasherTest {
    
    @Test
    void hashesPassword() {
        // Given
        final BCryptPasswordHasher hasher = new BCryptPasswordHasher();
        final String password = "my-secret-password";
        
        // When
        final String hash = hasher.hash(password);
        
        // Then
        MatcherAssert.assertThat(
            "Hash is not empty",
            hash,
            Matchers.not(Matchers.emptyString())
        );
        
        MatcherAssert.assertThat(
            "Hash starts with $2a$ (BCrypt)",
            hash.startsWith("$2a$"),
            new IsEqual<>(true)
        );
    }
    
    @Test
    void verifiesCorrectPassword() {
        // Given
        final BCryptPasswordHasher hasher = new BCryptPasswordHasher();
        final String password = "test123";
        final String hash = hasher.hash(password);
        
        // When
        final boolean verified = hasher.verify(password, hash);
        
        // Then
        MatcherAssert.assertThat(
            "Correct password is verified",
            verified,
            new IsEqual<>(true)
        );
    }
    
    @Test
    void rejectsWrongPassword() {
        // Given
        final BCryptPasswordHasher hasher = new BCryptPasswordHasher();
        final String password = "correct";
        final String hash = hasher.hash(password);
        
        // When
        final boolean verified = hasher.verify("wrong", hash);
        
        // Then
        MatcherAssert.assertThat(
            "Wrong password is rejected",
            verified,
            new IsEqual<>(false)
        );
    }
    
    @Test
    void handlesBadHash() {
        // Given
        final BCryptPasswordHasher hasher = new BCryptPasswordHasher();
        
        // When
        final boolean verified = hasher.verify("password", "invalid-hash");
        
        // Then
        MatcherAssert.assertThat(
            "Invalid hash returns false",
            verified,
            new IsEqual<>(false)
        );
    }
    
    @Test
    void generatesDifferentHashesForSamePassword() {
        // Given
        final BCryptPasswordHasher hasher = new BCryptPasswordHasher();
        final String password = "same-password";
        
        // When
        final String hash1 = hasher.hash(password);
        final String hash2 = hasher.hash(password);
        
        // Then
        MatcherAssert.assertThat(
            "Hashes are different due to random salt",
            hash1,
            Matchers.not(new IsEqual<>(hash2))
        );
        
        // But both verify correctly
        MatcherAssert.assertThat(
            "First hash verifies",
            hasher.verify(password, hash1),
            new IsEqual<>(true)
        );
        
        MatcherAssert.assertThat(
            "Second hash verifies",
            hasher.verify(password, hash2),
            new IsEqual<>(true)
        );
    }
}
