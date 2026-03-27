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
package com.auto1.pantera.asto;

import com.auto1.pantera.PanteraException;

/**
 * Helper object to read common metadata from {@link Meta}.
 * @since 1.11
 */
public final class MetaCommon {

    /**
     * Metadata.
     */
    private final Meta meta;

    /**
     * Ctor.
     * @param meta Metadata
     */
    public MetaCommon(final Meta meta) {
        this.meta = meta;
    }

    /**
     * Size.
     * @return Size
     */
    public long size() {
        return this.meta.read(Meta.OP_SIZE).orElseThrow(
            () -> new PanteraException("SIZE couldn't be read")
        ).longValue();
    }
}
