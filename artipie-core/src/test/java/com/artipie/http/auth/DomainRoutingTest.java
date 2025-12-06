/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.auth;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for domain-based routing in {@link Authentication.Joined}.
 */
class DomainRoutingTest {

    @Test
    void routesToCorrectProviderByDomain() {
        final AtomicInteger keycloakCalls = new AtomicInteger();
        final AtomicInteger oktaCalls = new AtomicInteger();
        
        final Authentication keycloak = new DomainFilteredAuth(
            (user, pass) -> {
                keycloakCalls.incrementAndGet();
                return "secret".equals(pass) 
                    ? Optional.of(new AuthUser(user, "keycloak")) 
                    : Optional.empty();
            },
            List.of("@company.com"),
            "keycloak"
        );
        
        final Authentication okta = new DomainFilteredAuth(
            (user, pass) -> {
                oktaCalls.incrementAndGet();
                return "secret".equals(pass) 
                    ? Optional.of(new AuthUser(user, "okta")) 
                    : Optional.empty();
            },
            List.of("@contractor.com"),
            "okta"
        );
        
        final Authentication joined = new Authentication.Joined(keycloak, okta);
        
        // Company user should only hit Keycloak
        final Optional<AuthUser> result1 = joined.user("user@company.com", "secret");
        assertTrue(result1.isPresent());
        assertEquals("keycloak", result1.get().authContext());
        assertEquals(1, keycloakCalls.get());
        assertEquals(0, oktaCalls.get());
        
        // Contractor should only hit Okta
        keycloakCalls.set(0);
        final Optional<AuthUser> result2 = joined.user("ext@contractor.com", "secret");
        assertTrue(result2.isPresent());
        assertEquals("okta", result2.get().authContext());
        assertEquals(0, keycloakCalls.get());
        assertEquals(1, oktaCalls.get());
    }

    @Test
    void stopsOnFailureWhenDomainMatches() {
        final AtomicInteger keycloakCalls = new AtomicInteger();
        final AtomicInteger oktaCalls = new AtomicInteger();
        
        // Both providers handle @company.com
        final Authentication keycloak = new DomainFilteredAuth(
            (user, pass) -> {
                keycloakCalls.incrementAndGet();
                return Optional.empty(); // Always fails
            },
            List.of("@company.com"),
            "keycloak"
        );
        
        final Authentication okta = new DomainFilteredAuth(
            (user, pass) -> {
                oktaCalls.incrementAndGet();
                return Optional.of(new AuthUser(user, "okta")); // Would succeed
            },
            List.of("@company.com"),
            "okta"
        );
        
        final Authentication joined = new Authentication.Joined(keycloak, okta);
        
        // Keycloak fails but has domain match - should NOT try Okta
        final Optional<AuthUser> result = joined.user("user@company.com", "wrong");
        assertFalse(result.isPresent());
        assertEquals(1, keycloakCalls.get());
        assertEquals(0, oktaCalls.get()); // Okta should not be called
    }

    @Test
    void fallsBackToCatchAll() {
        final AtomicInteger keycloakCalls = new AtomicInteger();
        final AtomicInteger fallbackCalls = new AtomicInteger();
        
        final Authentication keycloak = new DomainFilteredAuth(
            (user, pass) -> {
                keycloakCalls.incrementAndGet();
                return Optional.of(new AuthUser(user, "keycloak"));
            },
            List.of("@company.com"),
            "keycloak"
        );
        
        // Fallback with no domain restrictions
        final Authentication fallback = (user, pass) -> {
            fallbackCalls.incrementAndGet();
            return Optional.of(new AuthUser(user, "fallback"));
        };
        
        final Authentication joined = new Authentication.Joined(keycloak, fallback);
        
        // Unknown domain should skip Keycloak and hit fallback
        final Optional<AuthUser> result = joined.user("user@unknown.org", "any");
        assertTrue(result.isPresent());
        assertEquals("fallback", result.get().authContext());
        assertEquals(0, keycloakCalls.get());
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void localUsersMatchLocalPattern() {
        final AtomicInteger fileCalls = new AtomicInteger();
        final AtomicInteger keycloakCalls = new AtomicInteger();
        
        final Authentication keycloak = new DomainFilteredAuth(
            (user, pass) -> {
                keycloakCalls.incrementAndGet();
                return Optional.of(new AuthUser(user, "keycloak"));
            },
            List.of("@company.com"),
            "keycloak"
        );
        
        final Authentication file = new DomainFilteredAuth(
            (user, pass) -> {
                fileCalls.incrementAndGet();
                return "admin".equals(user) && "secret".equals(pass)
                    ? Optional.of(new AuthUser(user, "file"))
                    : Optional.empty();
            },
            List.of("local"),
            "file"
        );
        
        final Authentication joined = new Authentication.Joined(keycloak, file);
        
        // Local user (no @) should skip Keycloak, hit file
        final Optional<AuthUser> result = joined.user("admin", "secret");
        assertTrue(result.isPresent());
        assertEquals("file", result.get().authContext());
        assertEquals(0, keycloakCalls.get());
        assertEquals(1, fileCalls.get());
    }
}
