/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.error.InvalidDigestException;
import com.auto1.pantera.docker.misc.DigestedFlowable;

import java.util.concurrent.CompletableFuture;

/**
 * BlobSource which content is checked against digest on saving.
 */
public final class CheckedBlobSource implements BlobSource {

    /**
     * Blob content.
     */
    private final Content content;

    /**
     * Blob digest.
     */
    private final Digest digest;

    /**
     * @param content Blob content.
     * @param digest Blob digest.
     */
    public CheckedBlobSource(Content content, Digest digest) {
        this.content = content;
        this.digest = digest;
    }

    @Override
    public Digest digest() {
        return this.digest;
    }

    @Override
    public CompletableFuture<Void> saveTo(Storage storage, Key key) {
        final DigestedFlowable digested = new DigestedFlowable(this.content);
        final Content checked = new Content.From(
            this.content.size(),
            digested.doOnComplete(
                () -> {
                    final String calculated = digested.digest().hex();
                    final String expected = this.digest.hex();
                    if (!expected.equals(calculated)) {
                        throw new InvalidDigestException(
                            String.format("calculated: %s expected: %s", calculated, expected)
                        );
                    }
                }
            )
        );
        return storage.exists(key)
            .thenCompose(
                exists -> exists ? CompletableFuture.completedFuture(null)
                    : storage.save(key, checked)
            );
    }
}
