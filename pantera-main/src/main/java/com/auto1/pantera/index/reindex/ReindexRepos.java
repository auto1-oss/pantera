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
package com.auto1.pantera.index.reindex;

import java.nio.file.Path;
import java.util.Collection;

/**
 * The configured repositories, as the rebuild sees them.
 *
 * @since 2.2.9
 */
public interface ReindexRepos {

    /**
     * Names of every configured repository.
     * @return Names
     */
    Collection<String> names();

    /**
     * Whether a repository is configured right now.
     * @param name Repository name
     * @return True if it exists
     */
    boolean exists(String name);

    /**
     * Resolve what to scan for a repository.
     * @param name Repository name
     * @return Target
     */
    Target target(String name);

    /**
     * Scan target of a repository: its type and local storage root, or the
     * reason it cannot be scanned.
     *
     * @param type Repository type, may be null when skipped
     * @param root Local file-system root of its storage, null when skipped
     * @param skip Reason the repository is not scanned, null when scannable
     * @since 2.2.9
     */
    record Target(String type, Path root, String skip) {

        /**
         * Scannable target.
         * @param type Repository type
         * @param root Storage root on disk
         */
        public Target(final String type, final Path root) {
            this(type, root, null);
        }

        /**
         * Skipped target.
         * @param type Repository type, may be null
         * @param skip Reason
         */
        public Target(final String type, final String skip) {
            this(type, null, skip);
        }
    }
}
