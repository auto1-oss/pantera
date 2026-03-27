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
import java.util.LinkedList;
import java.util.List;

/**
 * Key that inserts a part.
 *
 * @since 1.9.1
 */
public final class KeyInsert extends Key.Wrap {

    /**
     * Ctor.
     * @param key Key
     * @param part Part to insert
     * @param index Index of insertion
     */
    public KeyInsert(final Key key, final String part, final int index) {
        super(
            new From(KeyInsert.insert(key, part, index))
        );
    }

    /**
     * Inserts part.
     * @param key Key
     * @param part Part to insert
     * @param index Index of insertion
     * @return List of parts
     */
    private static List<String> insert(final Key key, final String part, final int index) {
        final List<String> parts = new LinkedList<>(key.parts());
        parts.add(index, part);
        return parts;
    }
}
