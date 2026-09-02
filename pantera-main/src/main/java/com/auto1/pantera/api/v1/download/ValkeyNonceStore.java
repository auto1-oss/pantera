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

import com.auto1.pantera.cache.ValkeyConnection;
import io.lettuce.core.SetArgs;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Cluster-shared {@link NonceStore} on Valkey: {@code SET key NX PX ttl}
 * is atomic, so exactly one node's first redemption wins and a token
 * minted on one node cannot be replayed on another.
 *
 * <p>Fails CLOSED: if Valkey cannot be reached the redemption is refused
 * rather than silently degrading to "replayable".</p>
 *
 * @since 2.2.9
 */
public final class ValkeyNonceStore implements NonceStore {

    /**
     * Key namespace.
     */
    private static final String PREFIX = "pantera:download-nonce:";

    /**
     * Stored marker value.
     */
    private static final byte[] USED = {1};

    /**
     * Valkey connection.
     */
    private final ValkeyConnection valkey;

    /**
     * Remember window.
     */
    private final Duration ttl;

    /**
     * Ctor.
     *
     * @param valkey Valkey connection
     * @param ttl Remember window (at least the token TTL)
     */
    public ValkeyNonceStore(final ValkeyConnection valkey, final Duration ttl) {
        this.valkey = valkey;
        this.ttl = ttl;
    }

    @Override
    public CompletionStage<Boolean> consume(final String nonce) {
        return this.valkey.async()
            .set(
                PREFIX + nonce, USED,
                SetArgs.Builder.nx().px(this.ttl.toMillis())
            )
            .thenApply("OK"::equals)
            .exceptionally(err -> false);
    }
}
