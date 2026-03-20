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
package com.third.party.factory.first2;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.factory.PanteraStorageFactory;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StorageFactory;
import com.auto1.pantera.asto.memory.InMemoryStorage;

/**
 * Test storage factory.
 *
 * @since 1.13.0
 */
@PanteraStorageFactory("test-first")
public final class TestFirst2StorageFactory implements StorageFactory {
    @Override
    public Storage newStorage(final Config cfg) {
        return new InMemoryStorage();
    }
}
