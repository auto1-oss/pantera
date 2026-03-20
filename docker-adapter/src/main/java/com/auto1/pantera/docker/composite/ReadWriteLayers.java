/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.composite;

import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.asto.BlobSource;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Read-write {@link Layers} implementation.
 *
 * @since 0.3
 */
public final class ReadWriteLayers implements Layers {

    /**
     * Layers for reading.
     */
    private final Layers read;

    /**
     * Layers for writing.
     */
    private final Layers write;

    /**
     * Ctor.
     *
     * @param read Layers for reading.
     * @param write Layers for writing.
     */
    public ReadWriteLayers(final Layers read, final Layers write) {
        this.read = read;
        this.write = write;
    }

    @Override
    public CompletableFuture<Digest> put(final BlobSource source) {
        return this.write.put(source);
    }

    @Override
    public CompletableFuture<Void> mount(final Blob blob) {
        return this.write.mount(blob);
    }

    @Override
    public CompletableFuture<Optional<Blob>> get(final Digest digest) {
        return this.read.get(digest);
    }
}
