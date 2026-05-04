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
import javax.json.JsonObject;
import javax.json.JsonValue;
import com.auto1.pantera.db.dao.SettingsDao;
import com.auto1.pantera.http.log.EcsLogger;

/**
 * Seeds the settings table with spec defaults on first boot. Idempotent —
 * safe to run on every startup; only inserts rows whose keys are absent.
 *
 * <p>The default values come from {@link SettingsKey#defaultRepr()}, which
 * stores each default as a parseable JSON literal (e.g. {@code "\"h2\""}
 * for strings, {@code "1"} for ints, {@code "true"} for booleans).
 */
public final class SettingsBootstrap {

    private final SettingsDao dao;

    public SettingsBootstrap(final SettingsDao dao) {
        this.dao = dao;
    }

    public void seedIfMissing() {
        int seeded = 0;
        int existing = 0;
        for (SettingsKey k : SettingsKey.values()) {
            final boolean inserted = this.dao.putIfAbsent(
                k.key(), defaultJson(k.defaultRepr()), "bootstrap");
            if (inserted) {
                seeded++;
            } else {
                existing++;
            }
        }
        EcsLogger.info("com.auto1.pantera.settings.runtime")
            .message("SettingsBootstrap complete: " + seeded + " keys seeded, "
                + existing + " keys already present")
            .field("settings.seeded", seeded)
            .field("settings.existing", existing)
            .log();
    }

    private JsonObject defaultJson(final String repr) {
        // repr is a JSON literal (e.g. "\"h2\"", "1", "true").
        // Wrap it as {"value": <literal>}.
        final JsonValue literal = Json.createReader(new StringReader(repr)).readValue();
        return Json.createObjectBuilder().add("value", literal).build();
    }
}
