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
package com.auto1.pantera.gem;

import com.auto1.pantera.PanteraException;

/**
 * An uploaded gem version is already stored in an immutable repository.
 * The message is meant for the client.
 *
 * @since 2.2.10
 */
public final class GemExistsException extends PanteraException {

    private static final long serialVersionUID = 1L;

    /**
     * Ctor.
     * @param key Storage key of the stored gem
     */
    public GemExistsException(final String key) {
        super(
            String.format(
                "Repushing of gem versions is not allowed: %s already exists", key
            )
        );
    }
}
