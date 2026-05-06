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
     * Primary storage.
     */
    private final Storage storage;

    /**
     * Main constructor.
     * @param storage Asto storage object
     */
    public ConanRepo(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Updates repository incrementally.
     * @param prefix Repo prefix
     * @return Completable action
     */
    public Completable batchUpdateIncrementally(final Key prefix) {
        return null;
    }
}
