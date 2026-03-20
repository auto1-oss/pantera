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
package com.auto1.pantera.nuget.http.metadata;

import com.auto1.pantera.nuget.PackageIdentity;
import java.net.URL;

/**
 * Package content location.
 *
 * @since 0.1
 */
public interface ContentLocation {

    /**
     * Get URL for package content.
     *
     * @param identity Package identity.
     * @return URL for package content.
     */
    URL url(PackageIdentity identity);
}
