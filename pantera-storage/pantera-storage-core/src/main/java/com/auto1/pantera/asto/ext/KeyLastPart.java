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
package com.auto1.pantera.asto.ext;

import com.auto1.pantera.asto.Key;

/**
 * Last part of the storage {@link com.auto1.pantera.asto.Key}.
 * @since 0.24
 */
public final class KeyLastPart {

    /**
     * Origin key.
     */
    private final Key origin;

    /**
     * Ctor.
     * @param origin Key
     */
    public KeyLastPart(final Key origin) {
        this.origin = origin;
    }

    /**
     * Get last part of the key.
     * @return Key last part as string
     */
    public String get() {
        final String[] parts = this.origin.string().replaceAll("/$", "").split("/");
        return parts[parts.length - 1];
    }
}
