/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metadata;

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
