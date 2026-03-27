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
package com.auto1.pantera.nuget.http;

/**
 * Route that leads to resource.
 *
 * @since 0.1
 */
public interface Route {

    /**
     * Base path for resources.
     * If HTTP request path starts with given path, then this route may be used.
     *
     * @return Path prefix covered by this route.
     */
    String path();

    /**
     * Gets resource by path.
     *
     * @param path Path to resource.
     * @return Resource by path.
     */
    Resource resource(String path);
}
