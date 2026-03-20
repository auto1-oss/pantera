/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

/**
 * Cooldown metadata filtering infrastructure.
 *
 * <p>This package provides the core infrastructure for filtering package metadata
 * to remove blocked versions before serving to clients. This enables "version fallback"
 * where clients automatically resolve to older, unblocked versions.</p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.CooldownMetadataService} - Main service interface</li>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.MetadataParser} - Parse metadata from bytes</li>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.MetadataFilter} - Filter blocked versions</li>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.MetadataRewriter} - Serialize filtered metadata</li>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.FilteredMetadataCache} - Cache filtered metadata</li>
 * </ul>
 *
 * <h2>Per-Adapter Implementation</h2>
 * <p>Each adapter (NPM, Maven, PyPI, etc.) implements:</p>
 * <ul>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.MetadataRequestDetector} - Detect metadata requests</li>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.MetadataParser} - Parse format-specific metadata</li>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.MetadataFilter} - Filter format-specific metadata</li>
 *   <li>{@link com.auto1.pantera.cooldown.metadata.MetadataRewriter} - Serialize format-specific metadata</li>
 * </ul>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>P99 latency: &lt; 200ms for metadata filtering</li>
 *   <li>Cache hit rate: &gt; 90%</li>
 *   <li>Throughput: 1,500 requests/second</li>
 * </ul>
 *
 * @since 1.0
 */
package com.auto1.pantera.cooldown.metadata;
