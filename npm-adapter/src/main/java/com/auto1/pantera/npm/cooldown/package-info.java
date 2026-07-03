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
 * NPM cooldown metadata filtering implementation.
 *
 * <p>This package provides NPM-specific implementations of the cooldown metadata SPI:</p>
 * <ul>
 *   <li>{@link com.auto1.pantera.npm.cooldown.NpmMetadataParser} - Parses NPM registry JSON metadata</li>
 *   <li>{@link com.auto1.pantera.npm.cooldown.NpmMetadataFilter} - Filters blocked versions from metadata</li>
 *   <li>{@link com.auto1.pantera.npm.cooldown.NpmMetadataRewriter} - Serializes filtered metadata to JSON</li>
 *   <li>{@link com.auto1.pantera.npm.cooldown.NpmCooldownResponseFactory} - Builds 403 responses for blocked NPM packages</li>
 * </ul>
 *
 * <p>NPM metadata structure:</p>
 * <pre>
 * {
 *   "name": "package-name",
 *   "dist-tags": { "latest": "1.0.0", "beta": "2.0.0-beta.1" },
 *   "versions": {
 *     "1.0.0": { "name": "...", "version": "1.0.0", "dist": {...} },
 *     "1.0.1": { "name": "...", "version": "1.0.1", "dist": {...} }
 *   },
 *   "time": {
 *     "created": "2020-01-01T00:00:00.000Z",
 *     "modified": "2020-06-01T00:00:00.000Z",
 *     "1.0.0": "2020-01-01T00:00:00.000Z",
 *     "1.0.1": "2020-06-01T00:00:00.000Z"
 *   }
 * }
 * </pre>
 *
 * <p>When filtering blocked versions:</p>
 * <ol>
 *   <li>Blocked versions are removed from the "versions" object</li>
 *   <li>Corresponding timestamps are removed from the "time" object</li>
 *   <li>If "dist-tags.latest" points to a blocked version, it's updated to the highest unblocked version</li>
 * </ol>
 *
 * @since 1.0
 */
package com.auto1.pantera.npm.cooldown;
