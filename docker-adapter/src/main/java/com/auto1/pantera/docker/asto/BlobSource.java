/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Digest;

import java.util.concurrent.CompletableFuture;

/**
 * Source of blob that could be saved to {@link Storage} at desired location.
 *
 * @since 0.12
 */
public interface BlobSource {

    /**
     * Blob digest.
     *
     * @return Digest.
     */
    Digest digest();

    /**
     * Save blob to storage.
     *
     * @param storage Storage.
     * @param key     Destination for blob content.
     * @return Completion of save operation.
     */
    CompletableFuture<Void> saveTo(Storage storage, Key key);
}
