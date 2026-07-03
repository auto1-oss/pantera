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
package com.auto1.pantera.cooldown.config;

import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import java.util.Objects;

/**
 * Immutable bundle of per-adapter cooldown components.
 *
 * <p>Each repository type (maven, npm, pypi, docker, go, composer, gradle)
 * registers a bundle at startup. The bundle is looked up by repo type
 * when a request arrives, enabling the proxy layer to:
 * <ol>
 *   <li>Detect metadata requests via {@link #detector()}</li>
 *   <li>Route metadata through {@link com.auto1.pantera.cooldown.metadata.MetadataFilterService}
 *       using this bundle's parser/filter/rewriter</li>
 *   <li>Build format-appropriate 403 responses via {@link #responseFactory()}</li>
 * </ol>
 *
 * @param <T> Type of parsed metadata object (e.g. {@code Document} for Maven,
 *            {@code JsonNode} for npm/Composer, {@code List<String>} for Go)
 * @since 2.2.0
 */
public record CooldownAdapterBundle<T>(
    MetadataParser<T> parser,
    MetadataFilter<T> filter,
    MetadataRewriter<T> rewriter,
    MetadataRequestDetector detector,
    CooldownResponseFactory responseFactory
) {
    /**
     * Canonical constructor with null checks.
     */
    public CooldownAdapterBundle {
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(rewriter, "rewriter");
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(responseFactory, "responseFactory");
    }
}
