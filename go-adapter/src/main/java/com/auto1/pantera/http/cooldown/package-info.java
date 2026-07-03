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
 * Go cooldown metadata filtering implementation.
 *
 * <p>This package provides Go-specific implementations of the cooldown metadata SPI:</p>
 * <ul>
 *   <li>{@link com.auto1.pantera.http.cooldown.GoMetadataParser} -
 *       Parses Go {@code /@v/list} plain-text version list</li>
 *   <li>{@link com.auto1.pantera.http.cooldown.GoMetadataFilter} -
 *       Removes blocked versions from the version list</li>
 *   <li>{@link com.auto1.pantera.http.cooldown.GoMetadataRewriter} -
 *       Serializes filtered version list back to newline-separated text</li>
 *   <li>{@link com.auto1.pantera.http.cooldown.GoMetadataRequestDetector} -
 *       Detects {@code /@v/list} metadata requests</li>
 *   <li>{@link com.auto1.pantera.http.cooldown.GoCooldownResponseFactory} -
 *       Builds 403 responses for blocked Go modules</li>
 * </ul>
 *
 * <p>Go version list format (plain text, one version per line):</p>
 * <pre>
 * v0.1.0
 * v0.2.0
 * v1.0.0
 * </pre>
 *
 * @since 2.2.0
 */
package com.auto1.pantera.http.cooldown;
