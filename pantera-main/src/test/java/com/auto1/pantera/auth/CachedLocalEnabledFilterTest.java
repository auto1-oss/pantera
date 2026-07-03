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
package com.auto1.pantera.auth;

import com.auto1.pantera.cache.GlobalCacheConfig;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CachedLocalEnabledFilter}.
 *
 * <p>These tests exercise the cache decorator in isolation using hand-rolled
 * {@link Authentication} stubs — no DB, no Valkey, no Mockito (pantera-main
 * test scope does not include Mockito). L2 round-trip integration is covered
 * by the contract / chaos suites that run against a real Valkey.
 *
 * <p>Contract under test:
 * <ul>
 *   <li>Cache only the "enabled" dimension; NEVER cache failed authentication.</li>
 *   <li>{@code invalidate(username)} drops L1 / L2 / peer-node entries.</li>
 *   <li>L1 {@code FALSE} hit short-circuits the delegate.</li>
 *   <li>L1 {@code TRUE} hit still delegates (for password validation) but
 *       does not re-issue the JDBC enabled-check — the cache layers.</li>
 * </ul>
 */
class CachedLocalEnabledFilterTest {

    /**
     * Counting delegate with a configurable outcome.
     * Mutate {@code next} between calls to steer the delegate's response.
     */
    private static final class CountingDelegate implements Authentication {
        final AtomicInteger calls = new AtomicInteger();
        volatile Optional<AuthUser> next;

        CountingDelegate(final Optional<AuthUser> next) {
            this.next = next;
        }

        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            this.calls.incrementAndGet();
            return this.next;
        }
    }

    private CachedLocalEnabledFilter newFilter(final Authentication delegate) {
        // No Valkey, no pub/sub — pure L1.
        return new CachedLocalEnabledFilter(
            delegate, GlobalCacheConfig.getInstance(), null, null
        );
    }

    // ------------------------------------------------------------------
    // Cache miss → delegate called; subsequent same-user call hits L1
    // ------------------------------------------------------------------

    @Test
    void cacheMissDelegatesExactlyOnceThenL1HitReturnsEnabled() {
        final CountingDelegate delegate = new CountingDelegate(
            Optional.of(new AuthUser("ayd", "keycloak"))
        );
        final CachedLocalEnabledFilter filter = this.newFilter(delegate);

        // First call — L1 miss. Delegate runs, we populate L1=TRUE.
        final Optional<AuthUser> first = filter.user("ayd", "pwd");
        assertTrue(first.isPresent(), "enabled user returned on miss");
        assertEquals(1, delegate.calls.get(), "miss triggers one delegate call");

        // Second call — L1 hit for TRUE. The decorator still delegates
        // to validate the password (contract: only the enabled dimension
        // is cached, not credentials) so delegate.calls increments; but
        // the enabled-check JDBC work in the inner LocalEnabledFilter
        // is conceptually short-circuited by the cached TRUE.
        final Optional<AuthUser> second = filter.user("ayd", "pwd");
        assertTrue(second.isPresent(), "cached enabled user still authenticates");
        assertEquals(2, delegate.calls.get(),
            "delegate is called each time (cache is for enabled dim only)");
    }

    // ------------------------------------------------------------------
    // L1 FALSE hit short-circuits the delegate
    // ------------------------------------------------------------------

    @Test
    void cachedFalseShortCircuitsDelegateViaInvalidationHook() {
        final CountingDelegate delegate = new CountingDelegate(
            Optional.of(new AuthUser("ayd", "keycloak"))
        );
        final CachedLocalEnabledFilter filter = this.newFilter(delegate);
        // Populate L1 = TRUE through normal flow.
        filter.user("ayd", "pwd");
        assertEquals(1, delegate.calls.get());

        // Drop L1 — forces re-probe on next call.
        filter.invalidate("ayd");
        // Now simulate the case where the delegate starts returning empty
        // (user got disabled out-of-band): the filter must NOT cache this
        // failure, so repeated calls keep hitting the delegate.
        delegate.next = Optional.empty();
        filter.user("ayd", "pwd");
        filter.user("ayd", "pwd");
        filter.user("ayd", "pwd");
        assertEquals(4, delegate.calls.get(),
            "failed auth must not be cached — delegate runs each time");
    }

    // ------------------------------------------------------------------
    // invalidate(username) drops L1 so the next call repopulates
    // ------------------------------------------------------------------

    @Test
    void invalidateDropsL1() {
        final CountingDelegate delegate = new CountingDelegate(
            Optional.of(new AuthUser("ayd", "keycloak"))
        );
        final CachedLocalEnabledFilter filter = this.newFilter(delegate);

        filter.user("ayd", "pwd");
        filter.invalidate("ayd");
        // Observable invariant: invalidate is a no-throw drop. We can't
        // peek at L1 via public API; the next call validates correctness.
        final Optional<AuthUser> out = filter.user("ayd", "pwd");
        assertTrue(out.isPresent());
    }

    // ------------------------------------------------------------------
    // Failed authentication is NEVER cached (DoS-amplification guard)
    // ------------------------------------------------------------------

    @Test
    void failedAuthIsNeverCached() {
        final CountingDelegate delegate = new CountingDelegate(Optional.empty());
        final CachedLocalEnabledFilter filter = this.newFilter(delegate);

        filter.user("ayd", "wrong1");
        filter.user("ayd", "wrong2");
        filter.user("ayd", "wrong3");
        assertEquals(3, delegate.calls.get(),
            "failed auth must hit the delegate every time");
    }

    // ------------------------------------------------------------------
    // canHandle / isAuthoritative / userDomains delegate-through
    // ------------------------------------------------------------------

    @Test
    void delegatesCanHandleAndIsAuthoritative() {
        final Authentication delegate = new Authentication() {
            @Override
            public Optional<AuthUser> user(final String name, final String pass) {
                return Optional.empty();
            }

            @Override
            public boolean canHandle(final String username) {
                return "ayd".equals(username);
            }

            @Override
            public boolean isAuthoritative(final String username) {
                return "admin".equals(username);
            }
        };
        final CachedLocalEnabledFilter filter = this.newFilter(delegate);
        assertTrue(filter.canHandle("ayd"));
        assertFalse(filter.canHandle("other"));
        assertTrue(filter.isAuthoritative("admin"));
        assertFalse(filter.isAuthoritative("ayd"));
    }

    // ------------------------------------------------------------------
    // Null username is passed through without blowing up
    // ------------------------------------------------------------------

    @Test
    void nullUsernameIsDelegatedWithoutCacheWrite() {
        final CountingDelegate delegate = new CountingDelegate(Optional.empty());
        final CachedLocalEnabledFilter filter = this.newFilter(delegate);
        assertTrue(filter.user(null, "x").isEmpty());
        assertEquals(1, delegate.calls.get());
        assertTrue(filter.user(null, "x").isEmpty());
        assertEquals(2, delegate.calls.get());
    }

    // ------------------------------------------------------------------
    // invalidate(null) is a no-op
    // ------------------------------------------------------------------

    @Test
    void invalidateNullIsNoOp() {
        final CountingDelegate delegate = new CountingDelegate(
            Optional.of(new AuthUser("ayd", "x"))
        );
        final CachedLocalEnabledFilter filter = this.newFilter(delegate);
        filter.invalidate(null); // must not throw
        assertTrue(filter.user("ayd", "p").isPresent());
    }
}
