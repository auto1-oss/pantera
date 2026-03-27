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
package com.auto1.pantera.nuget.metadata;

/**
 * Nuspec xml metadata field.
 * @since 0.6
 */
public interface NuspecField {

    /**
     * Original raw value (as it was in xml).
     * @return String value
     */
    String raw();

    /**
     * Normalized value of the field.
     * @return Normalized value
     */
    String normalized();

}
