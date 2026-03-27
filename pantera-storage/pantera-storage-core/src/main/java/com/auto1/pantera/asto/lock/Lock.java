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
package com.auto1.pantera.asto.lock;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronous lock that might be successfully obtained by one thread only at a time.
 *
 * @since 0.24
 */
public interface Lock {

    /**
     * Acquire the lock.
     *
     * @return Completion of lock acquire operation.
     */
    CompletionStage<Void> acquire();

    /**
     * Release the lock.
     *
     * @return Completion of lock release operation.
     */
    CompletionStage<Void> release();
}
