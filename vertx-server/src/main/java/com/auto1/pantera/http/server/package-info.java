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
/**
 * Server-side decorators that sit on the outermost slice wrap, between
 * the Vert.x HTTP listener and the per-adapter routing core. The set is
 * deliberately small — anything specific to an adapter belongs in that
 * adapter's module.
 *
 * @since 2.2.0
 */
package com.auto1.pantera.http.server;
