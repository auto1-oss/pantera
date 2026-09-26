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

import java.util.concurrent.CompletionStage;

/**
 * Single-use nonce ledger for direct-download tokens: the first
 * {@link #consume(String)} of a nonce succeeds, every later one fails, for
 * at least the token TTL.
 *
 * <p>Asynchronous because the shared (Valkey) implementation is a network
 * round trip and the caller runs on the Vert.x event loop, which must never
 * block.</p>
 *
 * @since 2.2.9
 */
public interface NonceStore {

    /**
     * Atomically mark the nonce as used.
     *
     * @param nonce Token nonce
     * @return {@code true} exactly once per nonce (first use), else {@code false}
     */
    CompletionStage<Boolean> consume(String nonce);
}
