/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.auth;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link UserDomainMatcher}.
 */
class UserDomainMatcherTest {

    @Test
    void matchesAnyWhenNoPatterns() {
        final UserDomainMatcher matcher = new UserDomainMatcher();
        assertTrue(matcher.matches("user@company.com"));
        assertTrue(matcher.matches("admin"));
        assertTrue(matcher.matches("any@domain.org"));
    }

    @Test
    void matchesDomainSuffix() {
        final UserDomainMatcher matcher = new UserDomainMatcher(
            List.of("@company.com")
        );
        assertTrue(matcher.matches("user@company.com"));
        assertTrue(matcher.matches("admin@company.com"));
        assertFalse(matcher.matches("user@other.com"));
        assertFalse(matcher.matches("admin"));
    }

    @Test
    void matchesLocalUsers() {
        final UserDomainMatcher matcher = new UserDomainMatcher(
            List.of("local")
        );
        assertTrue(matcher.matches("admin"));
        assertTrue(matcher.matches("root"));
        assertFalse(matcher.matches("user@company.com"));
    }

    @Test
    void matchesWildcard() {
        final UserDomainMatcher matcher = new UserDomainMatcher(
            List.of("*")
        );
        assertTrue(matcher.matches("admin"));
        assertTrue(matcher.matches("user@company.com"));
        assertTrue(matcher.matches("anyone@anywhere.org"));
    }

    @Test
    void matchesMultiplePatterns() {
        final UserDomainMatcher matcher = new UserDomainMatcher(
            List.of("@company.com", "@contractor.com", "local")
        );
        assertTrue(matcher.matches("user@company.com"));
        assertTrue(matcher.matches("ext@contractor.com"));
        assertTrue(matcher.matches("admin"));
        assertFalse(matcher.matches("user@other.com"));
    }

    @Test
    void handlesNullAndEmpty() {
        final UserDomainMatcher matcher = new UserDomainMatcher(
            List.of("@company.com")
        );
        assertFalse(matcher.matches(null));
        assertFalse(matcher.matches(""));
    }

    @Test
    void matchesExactUsername() {
        final UserDomainMatcher matcher = new UserDomainMatcher(
            List.of("admin", "root")
        );
        assertTrue(matcher.matches("admin"));
        assertTrue(matcher.matches("root"));
        assertFalse(matcher.matches("user"));
        assertFalse(matcher.matches("admin@company.com"));
    }
}
