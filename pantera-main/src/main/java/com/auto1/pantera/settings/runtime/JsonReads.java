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
package com.auto1.pantera.settings.runtime;

import javax.json.JsonObject;
import java.util.Map;
import java.util.function.Function;

/** Internal helpers for reading typed values from a {@code Map<String, JsonObject>}. */
final class JsonReads {

    private JsonReads() { }

    static int intOr(final Map<String, JsonObject> rows, final String key, final int fallback) {
        final JsonObject row = rows.get(key);
        return row == null ? fallback : row.getInt("value");
    }

    static boolean boolOr(final Map<String, JsonObject> rows, final String key, final boolean fallback) {
        final JsonObject row = rows.get(key);
        return row == null ? fallback : row.getBoolean("value");
    }

    static <T> T valueOr(
        final Map<String, JsonObject> rows,
        final String key,
        final Function<JsonObject, T> extractor,
        final T fallback
    ) {
        final JsonObject row = rows.get(key);
        return row == null ? fallback : extractor.apply(row);
    }
}
