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
package com.auto1.pantera.composer;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.json.JsonObject;

/**
 * PHP Composer package.
 *
 * @since 0.1
 */
public interface Package {
    /**
     * Extract name from package.
     *
     * @return Package name.
     */
    CompletionStage<Name> name();

    /**
     * Extract version from package. Returns passed as a parameter value if present
     * in case of absence version.
     *
     * @param value Value in case of absence of version. This value can be empty.
     * @return Package version.
     */
    CompletionStage<Optional<String>> version(Optional<String> value);

    /**
     * Reads package content as JSON object.
     *
     * @return Package JSON object.
     */
    CompletionStage<JsonObject> json();
}
