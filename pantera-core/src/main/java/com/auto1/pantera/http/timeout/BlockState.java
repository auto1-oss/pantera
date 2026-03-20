/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
