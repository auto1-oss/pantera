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
 * PHP Composer cooldown metadata filtering implementation.
 *
 * <p>This package provides Composer-specific implementations of the cooldown metadata SPI:</p>
 * <ul>
 *   <li>{@link com.auto1.pantera.composer.cooldown.ComposerMetadataParser}
 *       - Parses Composer packages.json metadata</li>
 *   <li>{@link com.auto1.pantera.composer.cooldown.ComposerMetadataFilter}
 *       - Filters blocked versions from the version map</li>
 *   <li>{@link com.auto1.pantera.composer.cooldown.ComposerMetadataRewriter}
 *       - Serializes filtered metadata to JSON</li>
 *   <li>{@link com.auto1.pantera.composer.cooldown.ComposerMetadataRequestDetector}
 *       - Detects {@code /packages/} and {@code /p2/} metadata endpoints</li>
 *   <li>{@link com.auto1.pantera.composer.cooldown.ComposerCooldownResponseFactory}
 *       - Builds 403 responses for blocked Composer packages</li>
 * </ul>
 *
 * <p>Composer metadata structure ({@code /packages/{vendor}/{pkg}.json}
 * or {@code /p2/{vendor}/{pkg}.json}):</p>
 * <pre>
 * {
 *   "packages": {
 *     "vendor/package": {
 *       "1.0.0": {"name": "vendor/package", "version": "1.0.0", ...},
 *       "1.1.0": {"name": "vendor/package", "version": "1.1.0", ...}
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>When filtering blocked versions, blocked version keys are removed from the
 * {@code packages.{vendor/package}} object. Composer has no "latest" dist-tag;
 * the client resolves version constraints from the full version map.</p>
 *
 * @since 2.2.0
 */
package com.auto1.pantera.composer.cooldown;
