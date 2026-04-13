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
package com.auto1.pantera.http.auth;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Authentication.Joined} chain semantics, including the
 * {@code isAuthoritative} fall-through protection added in 2.1.0 to stop
 * a weak SSO password bypassing a strong local password for the same
 * username.
 *
 * <p>Every test uses pure Java fake providers — no DB or Testcontainers.
 * This keeps the semantics of the chain itself isolated from provider
 * implementation details.</p>
 */
final class JoinedAuthenticationTest {

    /** A provider that always fails and can be marked authoritative or not. */
    private static final class RejectingProvider implements Authentication {
        private final boolean authoritative;
        private final AtomicInteger calls = new AtomicInteger();

        RejectingProvider(final boolean authoritative) {
            this.authoritative = authoritative;
        }

        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            this.calls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public boolean isAuthoritative(final String username) {
            return this.authoritative;
        }
    }

    /** A provider that accepts any credentials (for the "would fall through" case). */
    private static final class AcceptingProvider implements Authentication {
        private final String label;
        private final AtomicInteger calls = new AtomicInteger();

        AcceptingProvider(final String label) {
            this.label = label;
        }

        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            this.calls.incrementAndGet();
            return Optional.of(new AuthUser(name, this.label));
        }
    }

    @Test
    void chainStopsOnAuthoritativeRejection() {
        final RejectingProvider local = new RejectingProvider(true);
        final AcceptingProvider sso = new AcceptingProvider("sso");
        final Authentication chain = new Authentication.Joined(List.of(local, sso));

        final Optional<AuthUser> result = chain.user("admin", "wrong-password");

        assertTrue(result.isEmpty(),
            "authoritative provider rejection must NOT fall through to SSO");
        assertEquals(1, local.calls.get(), "local provider was consulted");
        assertEquals(0, sso.calls.get(),
            "SSO provider must NOT be called after authoritative rejection");
    }

    @Test
    void chainFallsThroughWhenNotAuthoritative() {
        final RejectingProvider nonLocal = new RejectingProvider(false);
        final AcceptingProvider sso = new AcceptingProvider("sso");
        final Authentication chain = new Authentication.Joined(List.of(nonLocal, sso));

        final Optional<AuthUser> result = chain.user("sso-user", "sso-pass");

        assertTrue(result.isPresent(),
            "non-authoritative rejection should fall through to SSO");
        assertEquals("sso-user", result.get().name());
        assertEquals(1, nonLocal.calls.get());
        assertEquals(1, sso.calls.get(), "SSO provider consulted after fall-through");
    }

    @Test
    void chainReturnsImmediatelyOnFirstSuccess() {
        final AcceptingProvider first = new AcceptingProvider("first");
        final AcceptingProvider second = new AcceptingProvider("second");
        final Authentication chain = new Authentication.Joined(List.of(first, second));

        final Optional<AuthUser> result = chain.user("user", "pass");

        assertTrue(result.isPresent());
        assertEquals("first", result.get().authContext(),
            "first provider must win the race");
        assertEquals(1, first.calls.get());
        assertEquals(0, second.calls.get(),
            "second provider must NOT be called after first success");
    }

    @Test
    void authoritativeFlagOnlyAffectsFailureStop() {
        // Authoritative + success still returns that success immediately
        final AuthUser expected = new AuthUser("user", "auth");
        final Authentication authoritativeAccepter = new Authentication() {
            @Override
            public Optional<AuthUser> user(final String n, final String p) {
                return Optional.of(expected);
            }

            @Override
            public boolean isAuthoritative(final String username) {
                return true;
            }
        };
        final AcceptingProvider sso = new AcceptingProvider("sso");
        final Authentication chain = new Authentication.Joined(
            List.of(authoritativeAccepter, sso)
        );

        final Optional<AuthUser> result = chain.user("user", "pass");

        assertTrue(result.isPresent());
        assertEquals(0, sso.calls.get(),
            "second provider skipped after first success (authoritative or not)");
    }

    @Test
    void defaultIsAuthoritativeReturnsFalse() {
        final Authentication defaultProvider = new Authentication() {
            @Override
            public Optional<AuthUser> user(final String n, final String p) {
                return Optional.empty();
            }
        };
        assertFalse(defaultProvider.isAuthoritative("anyone"),
            "default interface method must return false");
    }

    @Test
    void chainSkipsProvidersThatCannotHandleUsername() {
        final Authentication domainScoped = new Authentication() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Optional<AuthUser> user(final String n, final String p) {
                this.calls.incrementAndGet();
                return Optional.empty();
            }

            @Override
            public boolean canHandle(final String username) {
                return username.contains("@");
            }

            AtomicInteger calls() {
                return this.calls;
            }
        };
        final AcceptingProvider fallback = new AcceptingProvider("fallback");
        final Authentication chain = new Authentication.Joined(
            List.of(domainScoped, fallback)
        );

        final Optional<AuthUser> result = chain.user("localuser", "pass");

        assertTrue(result.isPresent(), "fallback took over");
        assertEquals("fallback", result.get().authContext());
    }
}
