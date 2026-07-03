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
package  com.auto1.pantera.conan;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import io.reactivex.Completable;

/**
 * Conan repo frontend.
 * @since 0.1
 */
public final class ConanRepo {

    /**
     * Main constructor.
     * @param storage Asto storage object (reserved for future incremental update implementation)
     */
    public ConanRepo(final Storage storage) { // NOPMD UnusedFormalParameter - public API; storage will be wired in when batchUpdateIncrementally is implemented
        // storage parameter retained for forward-compatible API; current
        // batchUpdateIncrementally implementation is a stub.
    }

    /**
     * Updates repository incrementally.
     * @param prefix Repo prefix
     * @return Completable action
     */
    public Completable batchUpdateIncrementally(final Key prefix) { // NOPMD UnusedFormalParameter - public API; prefix will be wired in once implementation lands
        return null;
    }
}
