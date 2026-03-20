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
package com.auto1.pantera.asto.key;

import com.auto1.pantera.asto.Key;
import java.util.stream.Collectors;

/**
 * Key that excludes all occurrences of a part.
 * @implNote If part to exclude was not found, the class can return the origin key.
 * @since 1.8.1
 */
public final class KeyExcludeAll extends Key.Wrap {

    /**
     * Ctor.
     * @param key Key
     * @param part Part to exclude
     */
    public KeyExcludeAll(final Key key, final String part) {
        super(
            new Key.From(
                key.parts().stream()
                    .filter(p -> !p.equals(part))
                    .collect(Collectors.toList())
            )
        );
    }
}
