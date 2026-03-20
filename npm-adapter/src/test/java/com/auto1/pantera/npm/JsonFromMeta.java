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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;

/**
 * Json object from meta file for usage in tests.
 */
public final class JsonFromMeta {
    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Path to `meta.json` file.
     */
    private final Key path;

    /**
     * Ctor.
     * @param storage Storage
     * @param path Path to `meta.json` file
     */
    public JsonFromMeta(final Storage storage, final Key path) {
        this.storage = storage;
        this.path = path;
    }

    /**
     * Obtains json from meta file.
     * @return Json from meta file.
     */
    public JsonObject json() {
        return Json.createReader(
            new StringReader(
                this.storage.value(new Key.From(this.path, "meta.json")).join().asString()
            )
        ).readObject();
    }
}
