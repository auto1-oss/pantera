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
package com.auto1.pantera.cooldown;

/**
 * Persistence status for cooldown block entries.
 */
enum BlockStatus {
    ACTIVE,
    EXPIRED,
    INACTIVE;

    /**
     * Parses database value taking legacy entries into account.
     * @param value Database column value
     * @return Block status
     */
    static BlockStatus fromDatabase(final String value) {
        if ("MANUAL".equalsIgnoreCase(value)) {
            return INACTIVE;
        }
        return BlockStatus.valueOf(value);
    }
}
