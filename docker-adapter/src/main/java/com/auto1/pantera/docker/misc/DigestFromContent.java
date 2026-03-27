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
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.docker.Digest;
import java.util.concurrent.CompletionStage;

/**
 * Digest from content.
 * @since 0.2
 */
public final class DigestFromContent {

    /**
     * Content.
     */
    private final Content content;

    /**
     * Ctor.
     * @param content Content publisher
     */
    public DigestFromContent(final Content content) {
        this.content = content;
    }

    /**
     * Calculates digest from content.
     * @return CompletionStage from digest
     */
    public CompletionStage<Digest> digest() {
        return new ContentDigest(this.content, Digests.SHA256).hex().thenApply(Digest.Sha256::new);
    }

}
