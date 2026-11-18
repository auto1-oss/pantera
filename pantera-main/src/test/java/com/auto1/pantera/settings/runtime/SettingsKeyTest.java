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

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for the {@link SettingsKey} catalog. Pure value-object tests; no DB
 * or Vertx required.
 */
final class SettingsKeyTest {

    @Test
    void allDefaultReprsParseAsJsonValues() {
        for (SettingsKey k : SettingsKey.values()) {
            try (JsonReader reader = Json.createReader(new StringReader(k.defaultRepr()))) {
                final JsonValue v = reader.readValue();
                assertNotNull(v,
                    "defaultRepr for " + k.key() + " did not parse to a non-null JsonValue");
            }
        }
    }

    @Test
    void catalogDefaultsAreTheOnesTheBulkheadRunsWith() {
        // The API reports the catalog default for a key without a row, and a
        // reset to the default removes the row; the bulkhead then runs with
        // BulkheadTuning.defaults(). The two must not differ.
        final Map<String, JsonObject> rows = new HashMap<>();
        for (final SettingsKey key : SettingsKey.values()) {
            try (JsonReader reader = Json.createReader(new StringReader(key.defaultRepr()))) {
                rows.put(
                    key.key(),
                    Json.createObjectBuilder().add("value", reader.readValue()).build()
                );
            }
        }
        MatcherAssert.assertThat(
            BulkheadTuning.fromMap(rows), new IsEqual<>(BulkheadTuning.defaults())
        );
    }
}
