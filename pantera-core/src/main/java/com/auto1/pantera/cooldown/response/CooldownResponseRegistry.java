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
package com.auto1.pantera.cooldown.response;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of per-repo-type cooldown response factories.
 *
 * <p>Gradle repos reuse Maven's factory since gradle-adapter is an alias
 * for maven (gradle-adapter module was removed; see pantera-main/pom.xml).</p>
 *
 * @since 2.2.0
 */
public final class CooldownResponseRegistry {

    private final Map<String, CooldownResponseFactory> factories = new ConcurrentHashMap<>();

    /**
     * Register a factory for a repository type.
     *
     * @param repoType Repository type identifier
     * @param factory Factory instance
     */
    public void register(final String repoType, final CooldownResponseFactory factory) {
        this.factories.put(repoType, factory);
    }

    /**
     * Register a factory for a repository type and additional aliases.
     * Useful when multiple repo types share the same format (e.g. Gradle reuses Maven).
     *
     * @param factory Factory instance
     * @param aliases Additional type identifiers that map to the same factory
     */
    public void register(final CooldownResponseFactory factory, final String... aliases) {
        this.factories.put(factory.repoType(), factory);
        for (final String alias : aliases) {
            this.factories.put(alias, factory);
        }
    }

    /**
     * Get factory for a repository type.
     *
     * @param repoType Repository type identifier
     * @return Factory, or null if not registered
     */
    public CooldownResponseFactory get(final String repoType) {
        return this.factories.get(repoType);
    }

    /**
     * Returns the set of registered repository types.
     *
     * @return Unmodifiable set of registered types
     */
    public Set<String> registeredTypes() {
        return Set.copyOf(this.factories.keySet());
    }
}
