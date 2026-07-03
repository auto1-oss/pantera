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
package com.auto1.pantera.cooldown.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of per-repo-type cooldown adapter bundles.
 *
 * <p>Populated once at startup by the wiring layer ({@code CooldownWiring}),
 * then queried on every proxy request to obtain the adapter-specific
 * parser, filter, rewriter, detector, and response factory.</p>
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}.</p>
 *
 * @since 2.2.0
 */
public final class CooldownAdapterRegistry {

    /**
     * Singleton instance.
     */
    private static final CooldownAdapterRegistry INSTANCE = new CooldownAdapterRegistry();

    /**
     * Bundles by canonical repo type (e.g. "maven", "npm", "gradle").
     */
    private final Map<String, CooldownAdapterBundle<?>> bundles;

    private CooldownAdapterRegistry() {
        this.bundles = new ConcurrentHashMap<>();
    }

    /**
     * Get singleton instance.
     *
     * @return Registry instance
     */
    public static CooldownAdapterRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register an adapter bundle for a repository type.
     *
     * @param repoType Canonical repo type identifier (e.g. "maven", "npm")
     * @param bundle Adapter bundle
     * @param <T> Metadata type
     */
    public <T> void register(final String repoType, final CooldownAdapterBundle<T> bundle) {
        this.bundles.put(repoType, bundle);
    }

    /**
     * Register an adapter bundle for a repository type and additional aliases.
     * Useful when multiple types share the same components (e.g. gradle reuses maven).
     *
     * @param repoType Primary repo type identifier
     * @param bundle Adapter bundle
     * @param aliases Additional type identifiers that map to the same bundle
     * @param <T> Metadata type
     */
    public <T> void register(
        final String repoType,
        final CooldownAdapterBundle<T> bundle,
        final String... aliases
    ) {
        this.bundles.put(repoType, bundle);
        for (final String alias : aliases) {
            this.bundles.put(alias, bundle);
        }
    }

    /**
     * Get adapter bundle for a repository type.
     *
     * @param repoType Repository type identifier
     * @return Bundle if registered, empty otherwise
     */
    public Optional<CooldownAdapterBundle<?>> get(final String repoType) {
        return Optional.ofNullable(this.bundles.get(repoType));
    }

    /**
     * Returns the set of registered repository types.
     *
     * @return Unmodifiable set of registered types
     */
    public Set<String> registeredTypes() {
        return Set.copyOf(this.bundles.keySet());
    }

    /**
     * Clear all registrations. For testing only.
     */
    public void clear() {
        this.bundles.clear();
    }
}
