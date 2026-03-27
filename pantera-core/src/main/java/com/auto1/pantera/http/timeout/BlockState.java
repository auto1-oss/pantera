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
package com.auto1.pantera.http.timeout;

import java.time.Instant;

/**
 * Immutable block state for a remote endpoint.
 *
 * @since 1.20.13
 */
record BlockState(int failureCount, int fibonacciIndex, Instant blockedUntil, Status status) {

    enum Status { ONLINE, BLOCKED, PROBING }

    static BlockState online() {
        return new BlockState(0, 0, Instant.MIN, Status.ONLINE);
    }
}
