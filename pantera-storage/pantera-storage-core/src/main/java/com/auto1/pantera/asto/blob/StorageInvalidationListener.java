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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Key;

/**
 * Callback invoked when {@link StorageInvalidationBus} delivers a peer's
 * cross-node coherence invalidation (spec {@code WS1-storage-for-scale.md}
 * &sect;3.E).
 *
 * @since 2.3.0
 */
@FunctionalInterface
public interface StorageInvalidationListener {

    /**
     * A peer committed (write-through/write-back upload confirmed durable)
     * or deleted {@code key}.
     *
     * @param key Key the peer committed or deleted.
     * @param versionToken Opaque version marker the publisher attached (see
     *  {@code CachedBlobStorage}'s {@code StorageInvalidationToken}) -- never
     *  interpreted by {@link StorageInvalidationBus} itself.
     */
    void invalidated(Key key, String versionToken);
}
