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
package com.auto1.pantera.api.v1.download;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-local {@link NonceStore}: consumed nonces are remembered for the
 * TTL and then forgotten. Bounded so an attacker cannot grow the ledger
 * without bound (a nonce only enters the ledger after its signature
 * verified, so growth is limited to genuinely issued tokens anyway).
 *
 * <p>Correct for a single node; multi-node deployments use
 * {@link ValkeyNonceStore} so a token minted on one node cannot be
 * replayed on another.</p>
 *
 * @since 2.2.9
 */
public final class InMemoryNonceStore implements NonceStore {

    /**
     * Hard cap on remembered nonces.
     */
    private static final int MAX_ENTRIES = 100_000;

    /**
     * Nonce → expiry (millis on {@link #clock}).
     */
    private final Map<String, Long> used;

    /**
     * How long a consumed nonce stays remembered.
     */
    private final long ttlMillis;

    /**
     * Millisecond clock; injectable for tests.
     */
    private final LongSupplier clock;

    /**
     * Ctor with the system clock.
     *
     * @param ttl Remember window (at least the token TTL)
     */
    public InMemoryNonceStore(final Duration ttl) {
        this(ttl, System::currentTimeMillis);
    }

    /**
     * Ctor.
     *
     * @param ttl Remember window (at least the token TTL)
     * @param clock Millisecond clock
     */
    public InMemoryNonceStore(final Duration ttl, final LongSupplier clock) {
        this.used = new ConcurrentHashMap<>();
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
    }

    @Override
    public CompletionStage<Boolean> consume(final String nonce) {
        final long now = this.clock.getAsLong();
        this.expire(now);
        final Long previous = this.used.putIfAbsent(nonce, now + this.ttlMillis);
        return CompletableFuture.completedFuture(previous == null);
    }

    /**
     * Drop expired entries; if still over the cap, drop the oldest so the
     * ledger stays bounded.
     *
     * @param now Current millis
     */
    private void expire(final long now) {
        final Iterator<Map.Entry<String, Long>> iter = this.used.entrySet().iterator();
        while (iter.hasNext()) {
            if (iter.next().getValue() <= now) {
                iter.remove();
            }
        }
        if (this.used.size() >= MAX_ENTRIES) {
            this.used.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .ifPresent(oldest -> this.used.remove(oldest.getKey()));
        }
    }
}
