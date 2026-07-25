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
package com.auto1.pantera.docker;

import com.auto1.pantera.asto.Content;

import java.util.Optional;

/**
 * Docker repository manifest tags.
 *
 * @since 0.8
 */
public interface Tags {

    /**
     * Read tags in JSON format.
     *
     * @return Tags in JSON format.
     */
    Content json();

    /**
     * Whether more tags exist beyond this page (pagination was truncated).
     * Implementations that don't produce a bounded page (proxy pass-through,
     * generic wrappers) default to {@code false}.
     *
     * @return True when a further page is available.
     */
    default boolean hasNext() {
        return false;
    }

    /**
     * Cursor (last tag on this page) to resume pagination from via the {@code last}
     * query parameter. Present only when {@link #hasNext()} is {@code true}.
     *
     * @return Last tag of the current page.
     */
    default Optional<String> nextCursor() {
        return Optional.empty();
    }
}
