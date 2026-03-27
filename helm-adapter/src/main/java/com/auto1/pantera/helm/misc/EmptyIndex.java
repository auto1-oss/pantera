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
package com.auto1.pantera.helm.misc;

import com.auto1.pantera.asto.Content;
import java.nio.charset.StandardCharsets;

/**
 * Provides empty index file.
 * @since 0.3
 */
public final class EmptyIndex {
    /**
     * Content of index file.
     */
    private final String index;

    /**
     * Ctor.
     */
    public EmptyIndex() {
        this.index = String.format(
            "apiVersion: v1\ngenerated: %s\nentries:\n",
            new DateTimeNow().asString()
        );
    }

    /**
     * Index file as content.
     * @return Index file as content.
     */
    public Content asContent() {
        return new Content.From(this.index.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Index file as string.
     * @return Index file as string.
     */
    public String asString() {
        return this.index;
    }
}
