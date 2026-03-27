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
package com.auto1.pantera.helm.test;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.helm.metadata.IndexYaml;
import com.auto1.pantera.helm.metadata.IndexYamlMapping;

/**
 * Class for using test scope. It helps to get content of index from storage.
 */
public final class ContentOfIndex {
    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Storage
     */
    public ContentOfIndex(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Obtains index from storage by default key.
     * @return Index file from storage.
     */
    public IndexYamlMapping index() {
        return this.index(IndexYaml.INDEX_YAML);
    }

    /**
     * Obtains index from storage by specified path.
     * @param path Path to index file
     * @return Index file from storage.
     */
    public IndexYamlMapping index(final Key path) {
        return new IndexYamlMapping(this.storage.value(path).join().asString());
    }
}
