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
package com.auto1.pantera.auth.failedlogins;

import java.time.Instant;

/**
 * A user name with a recorded lock.
 *
 * @param name User name as it is counted (lower case)
 * @param lockedAt Time of the lock
 * @param failures Failed logins counted
 * @param lastFailure Time of the last failed login
 * @param origin Where the last failed login came from
 * @since 2.2.15
 */
public record LockedName(
    String name, Instant lockedAt, int failures, Instant lastFailure, LoginOrigin origin
) {
}
