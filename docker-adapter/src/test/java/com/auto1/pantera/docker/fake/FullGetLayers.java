/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.fake;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.asto.AstoBlob;
import com.auto1.pantera.docker.asto.BlobSource;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Layers implementation that contains blob.
 *
 * @since 0.3
 */
public final class FullGetLayers implements Layers {

    @Override
    public CompletableFuture<Digest> put(final BlobSource source) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Void> mount(final Blob blob) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Blob>> get(final Digest digest) {
        return CompletableFuture.completedFuture(
            Optional.of(new AstoBlob(new InMemoryStorage(), new Key.From("test"), digest))
        );
    }
}
