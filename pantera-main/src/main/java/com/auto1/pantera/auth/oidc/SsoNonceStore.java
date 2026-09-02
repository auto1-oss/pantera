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
package com.auto1.pantera.auth.oidc;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-login OIDC nonces, keyed by the OAuth {@code state} the browser
 * round-trips. The redirect issues a nonce (sent to the IdP in the
 * authorize request); the callback consumes it by state — single use,
 * bounded lifetime — and the id_token's {@code nonce} claim must match,
 * binding the token to this browser's authorization request.
 *
 * <p>In-memory: a login must complete on the node that started it, which
 * holds because the redirect and callback are one browser session with
 * sticky routing — and a miss simply fails closed (login is retried).</p>
 *
 * @since 2.2.9
 */
public final class SsoNonceStore {

    /**
     * Cap on outstanding logins so unbounded redirect spam cannot grow heap.
     */
    private static final int MAX_PENDING = 10_000;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long ttlNanos;
    private final LongSupplier clock;

    /**
     * Ctor with the system clock.
     * @param ttl How long a started login may take to complete
     */
    public SsoNonceStore(final Duration ttl) {
        this(ttl, System::nanoTime);
    }

    /**
     * Ctor with an injectable clock (tests).
     * @param ttl How long a started login may take to complete
     * @param clock Monotonic nano clock
     */
    public SsoNonceStore(final Duration ttl, final LongSupplier clock) {
        this.ttlNanos = ttl.toNanos();
        this.clock = clock;
    }

    /**
     * Issue a fresh nonce for a state.
     * @param state OAuth state of the new login
     * @return The nonce to send in the authorize request
     */
    public String issue(final String state) {
        this.evictExpired();
        if (this.pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("too many pending SSO logins");
        }
        final byte[] raw = new byte[24];
        this.random.nextBytes(raw);
        final String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        this.pending.put(state, new Pending(nonce, this.clock.getAsLong()));
        return nonce;
    }

    /**
     * Consume the nonce for a state (single use).
     * @param state OAuth state returned by the browser
     * @return The nonce, or empty when unknown, already used, or expired
     */
    public Optional<String> consume(final String state) {
        if (state == null) {
            return Optional.empty();
        }
        final Pending entry = this.pending.remove(state);
        if (entry == null || this.clock.getAsLong() - entry.issuedNanos() > this.ttlNanos) {
            return Optional.empty();
        }
        return Optional.of(entry.nonce());
    }

    /**
     * Generate a cryptographically random OAuth state.
     * @return Random state
     */
    public String newState() {
        final byte[] raw = new byte[24];
        this.random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private void evictExpired() {
        final long now = this.clock.getAsLong();
        this.pending.entrySet().removeIf(e -> now - e.getValue().issuedNanos() > this.ttlNanos);
    }

    /**
     * A started login.
     * @param nonce Issued nonce
     * @param issuedNanos Issue time
     */
    private record Pending(String nonce, long issuedNanos) {
    }
}
