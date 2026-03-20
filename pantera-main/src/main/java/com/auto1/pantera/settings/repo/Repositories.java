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
package com.auto1.pantera.settings.repo;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Pantera repositories registry.
 */
public interface Repositories {

    /**
     * Gets repository config by name.
     *
     * @param name Repository name
     * @return {@code Optional}, that contains repository configuration
     * or {@code Optional.empty()} if one is not found.
     */
    Optional<RepoConfig> config(String name);

    /**
     * Gets collection repositories configurations.
     *
     * @return Collection repository's configurations.
     */
    Collection<RepoConfig> configs();

    /**
     * Refresh repositories asynchronously.
     *
     * @return future completed when reload finishes
     */
    default CompletableFuture<Void> refreshAsync() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Refresh repositories synchronously.
     * @deprecated Prefer {@link #refreshAsync()} to avoid blocking critical threads.
     */
    @Deprecated
    default void refresh() {
        refreshAsync().join();
    }
}
