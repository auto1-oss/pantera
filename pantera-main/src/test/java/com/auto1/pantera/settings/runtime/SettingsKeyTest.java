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
import javax.json.Json;
import javax.json.JsonReader;
import javax.json.JsonValue;
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
}
