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

import java.util.Collections;
import java.util.Set;

/**
 * Exception thrown when all versions of a package are blocked by cooldown.
 * Callers should handle this by returning HTTP 403 with appropriate error details.
 *
 * @since 1.0
 */
public final class AllVersionsBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Package name.
     */
    private final String packageName;

    /**
     * Set of blocked versions.
     */
    private final Set<String> blockedVersions;

    /**
     * Constructor.
     *
     * @param packageName Package name
     * @param blockedVersions Set of all blocked versions
     */
    public AllVersionsBlockedException(
        final String packageName,
        final Set<String> blockedVersions
    ) {
        super(String.format(
            "All %d versions of package '%s' are blocked by cooldown",
            blockedVersions.size(),
            packageName
        ));
        this.packageName = packageName;
        this.blockedVersions = Collections.unmodifiableSet(blockedVersions);
    }

    /**
     * Get package name.
     *
     * @return Package name
     */
    public String packageName() {
        return this.packageName;
    }

    /**
     * Get blocked versions.
     *
     * @return Unmodifiable set of blocked versions
     */
    public Set<String> blockedVersions() {
        return this.blockedVersions;
    }
}
