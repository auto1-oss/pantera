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
package com.auto1.pantera.asto;

import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.StorageWhiteboxVerification;

/**
 * In memory storage verification test.
 *
 * @since 1.14.0
 */
public final class InMemoryStorageVerificationTest extends StorageWhiteboxVerification {

    @Override
    protected Storage newStorage() throws Exception {
        return new InMemoryStorage();
    }
}
