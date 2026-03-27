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
package com.auto1.pantera.docker;

import com.auto1.pantera.asto.Content;

import java.util.concurrent.CompletableFuture;

/**
 * Blob stored in repository.
 *
 * @since 0.2
 */
public interface Blob {

    /**
     * Blob digest.
     *
     * @return Digest.
     */
    Digest digest();

    /**
     * Read blob size.
     *
     * @return Size of blob in bytes.
     */
    CompletableFuture<Long> size();

    /**
     * Read blob content.
     *
     * @return Content.
     */
    CompletableFuture<Content> content();
}
