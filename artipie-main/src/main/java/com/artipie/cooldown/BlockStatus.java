/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

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
