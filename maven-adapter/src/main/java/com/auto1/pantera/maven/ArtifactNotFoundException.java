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
package com.auto1.pantera.maven;

import com.auto1.pantera.asto.Key;

/**
 * This exception can be thrown when artifact was not found.
 * @since 0.5
 */
@SuppressWarnings("serial")
public final class ArtifactNotFoundException extends IllegalStateException {

    /**
     * New exception with artifact key.
     * @param artifact Artifact key
     */
    public ArtifactNotFoundException(final Key artifact) {
        this(String.format("Artifact '%s' was not found", artifact.string()));
    }

    /**
     * New exception with message.
     * @param msg Message
     */
    public ArtifactNotFoundException(final String msg) {
        super(msg);
    }
}
