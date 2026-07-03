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
 * Maven cooldown metadata filtering implementation.
 *
 * <p>This package provides Maven-specific implementations of the cooldown metadata SPI:</p>
 * <ul>
 *   <li>{@link com.auto1.pantera.maven.cooldown.MavenMetadataParser}
 *       - Parses {@code maven-metadata.xml} via DOM</li>
 *   <li>{@link com.auto1.pantera.maven.cooldown.MavenMetadataFilter}
 *       - Filters blocked versions from metadata</li>
 *   <li>{@link com.auto1.pantera.maven.cooldown.MavenMetadataRewriter}
 *       - Serializes filtered metadata back to XML</li>
 *   <li>{@link com.auto1.pantera.maven.cooldown.MavenMetadataRequestDetector}
 *       - Detects {@code maven-metadata.xml} requests</li>
 *   <li>{@link com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory}
 *       - Builds 403 responses for blocked Maven artifacts</li>
 * </ul>
 *
 * @since 2.2.0
 */
package com.auto1.pantera.maven.cooldown;
