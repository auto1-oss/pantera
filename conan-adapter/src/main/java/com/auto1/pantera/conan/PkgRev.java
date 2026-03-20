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
package  com.auto1.pantera.conan;

import java.time.Instant;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Conan package revision data.
 * @since 0.1
 */
public class PkgRev {

    /**
     * Revision json field.
     */
    private static final String REVISION = "revision";

    /**
     * Timestamp json field. Uses ISO 8601 format.
     */
    private static final String TIMESTAMP = "time";

    /**
     * Revision number of the package.
     */
    private final int revision;

    /**
     * Timestamp for revision info.
     */
    private final Instant instant;

    /**
     * Initializes new instance.
     * @param revision Revision number of the package.
     */
    PkgRev(final int revision) {
        this.revision = revision;
        this.instant = Instant.now();
    }

    /**
     * Creates new revision object for json index file. Time is in ISO 8601 format.
     * @return JsonObject with revision info.
     */
    public JsonObject toJson() {
        return Json.createObjectBuilder()
            .add(PkgRev.REVISION, Integer.toString(this.revision))
            .add(PkgRev.TIMESTAMP, this.instant.toString()).build();
    }
}
