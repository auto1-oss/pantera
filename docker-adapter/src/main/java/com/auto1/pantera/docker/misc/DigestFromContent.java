/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
