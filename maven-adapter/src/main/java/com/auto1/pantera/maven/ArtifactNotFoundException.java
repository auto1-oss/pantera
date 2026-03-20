/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
