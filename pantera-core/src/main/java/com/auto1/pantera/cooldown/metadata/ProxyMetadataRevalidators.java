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
package com.auto1.pantera.cooldown.metadata;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of per-repository raw-metadata revalidators.
 *
 * <p>Each proxy adapter that caches raw upstream metadata (npm packuments,
 * PyPI simple indexes, maven-metadata.xml) registers, under its repository
 * name, a hook that forces the cached copy of one package's metadata to be
 * revalidated against the upstream through the adapter's OWN refresh path
 * (conditional fetch, write hooks and all). The admin "refresh package"
 * tool drives these hooks; nothing on the serving path reads this
 * registry.</p>
 *
 * <p>A repository slice rebuilt on config reload re-registers under the same
 * name, replacing the previous hook; a removed repository is unregistered by
 * the slice cache.</p>
 *
 * @since 2.2.9
 */
public final class ProxyMetadataRevalidators {

    /**
     * Singleton.
     */
    private static final ProxyMetadataRevalidators INSTANCE =
        new ProxyMetadataRevalidators();

    /**
     * Hooks by repository name.
     */
    private final Map<String, Revalidator> hooks = new ConcurrentHashMap<>();

    /**
     * Singleton ctor.
     */
    private ProxyMetadataRevalidators() {
    }

    /**
     * Shared instance.
     *
     * @return Registry
     */
    public static ProxyMetadataRevalidators instance() {
        return INSTANCE;
    }

    /**
     * Register (or replace) the hook of a repository.
     *
     * @param repoName Repository name
     * @param hook Revalidator
     */
    public void register(final String repoName, final Revalidator hook) {
        this.hooks.put(repoName, hook);
    }

    /**
     * Drop the hook of a repository.
     *
     * @param repoName Repository name
     */
    public void remove(final String repoName) {
        this.hooks.remove(repoName);
    }

    /**
     * Hook of a repository.
     *
     * @param repoName Repository name
     * @return Hook, empty when the repository registered none (not a
     *  metadata-caching proxy, or its slice has not been built yet)
     */
    public Optional<Revalidator> forRepo(final String repoName) {
        return Optional.ofNullable(this.hooks.get(repoName));
    }

    /**
     * Forces revalidation of one package's cached raw metadata.
     *
     * @since 2.2.9
     */
    @FunctionalInterface
    public interface Revalidator {

        /**
         * Revalidate the cached raw metadata of a package.
         *
         * @param packageName Package name as a client names it
         * @return Future of a short snake_case outcome, e.g.
         *  {@code refreshed}, {@code not_modified}, {@code not_cached},
         *  {@code invalidated}, {@code upstream_gone}
         */
        CompletableFuture<String> revalidate(String packageName);
    }
}
