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
     * Whether every source of this listing answered. A listing joined from
     * sources where one could not be read (an upstream failure) is
     * incomplete: an empty result is then not proof that the name is
     * unknown.
     *
     * @return False when at least one source failed
     */
    default boolean complete() {
        return true;
    }

    /**
     * Whether this source holds the repository name at all, independent of
     * the requested page. A source that can tell (hosted storage: the image
     * has tags) answers false for a name it does not hold, so a cursor page
     * is 404 NAME_UNKNOWN instead of an empty 200. A source that cannot tell
     * (a remote listing) answers true, and only an empty first page is taken
     * as NAME_UNKNOWN.
     *
     * @return False when the source proves the name absent
     */
    default boolean known() {
        return true;
    }
}
