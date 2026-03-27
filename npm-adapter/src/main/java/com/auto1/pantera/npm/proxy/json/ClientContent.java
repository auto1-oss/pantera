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
package com.auto1.pantera.npm.proxy.json;

/**
 * Client package content representation.
 *
 * @since 0.1
 */
public final class ClientContent extends TransformedContent {
    /**
     * Base URL where adapter is published.
     */
    private final String url;

    /**
     * Ctor.
     * @param content Package content to be transformed
     * @param url Base URL where adapter is published
     */
    public ClientContent(final String content, final String url) {
        super(content);
        this.url = url;
    }

    @Override
    String transformRef(final String ref) {
        return this.url.concat(ref);
    }
}
