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
package com.auto1.pantera.npm;

/**
 * A publish into an immutable npm repository would overwrite a version
 * that is already published.
 *
 * @since 2.2.10
 */
public final class VersionExistsException extends IllegalStateException {

    /**
     * Serial version.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Published version.
     */
    private final String version;

    /**
     * Ctor.
     * @param version Version that is already published
     */
    public VersionExistsException(final String version) {
        super(String.format("cannot publish over the previously published version %s", version));
        this.version = version;
    }

    /**
     * Version that is already published.
     * @return Version
     */
    public String version() {
        return this.version;
    }
}
