/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
