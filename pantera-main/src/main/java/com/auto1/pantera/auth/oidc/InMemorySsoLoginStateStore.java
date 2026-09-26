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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Single-node {@link SsoLoginStateStore} over the in-memory
 * {@link SsoNonceStore}.
 *
 * @since 2.2.9
 */
public final class InMemorySsoLoginStateStore implements SsoLoginStateStore {

    /**
     * Backing store.
     */
    private final SsoNonceStore nonces;

    /**
     * Ctor.
     * @param nonces Backing store
     */
    public InMemorySsoLoginStateStore(final SsoNonceStore nonces) {
        this.nonces = nonces;
    }

    @Override
    public String newState() {
        return this.nonces.newState();
    }

    @Override
    public CompletionStage<String> issue(final String state) {
        try {
            return CompletableFuture.completedFuture(this.nonces.issue(state));
        } catch (final IllegalStateException full) {
            return CompletableFuture.failedFuture(full);
        }
    }

    @Override
    public CompletionStage<Optional<String>> consume(final String state) {
        return CompletableFuture.completedFuture(this.nonces.consume(state));
    }
}
