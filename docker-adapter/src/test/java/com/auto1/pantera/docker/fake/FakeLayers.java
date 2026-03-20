/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.fake;

import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.asto.BlobSource;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Auxiliary class for tests for {@link com.auto1.pantera.docker.cache.CacheLayers}.
 *
 * @since 0.5
 */
public final class FakeLayers implements Layers {
    /**
     * Layers.
     */
    private final Layers layers;

    /**
     * Ctor.
     *
     * @param type Type of layers.
     */
    public FakeLayers(final String type) {
        this.layers = layersFromType(type);
    }

    @Override
    public CompletableFuture<Digest> put(final BlobSource source) {
        return this.layers.put(source);
    }

    @Override
    public CompletableFuture<Void> mount(final Blob blob) {
        return this.layers.mount(blob);
    }

    @Override
    public CompletableFuture<Optional<Blob>> get(final Digest digest) {
        return this.layers.get(digest);
    }

    /**
     * Creates layers.
     *
     * @param type Type of layers.
     * @return Layers.
     */
    private static Layers layersFromType(final String type) {
        final Layers tmplayers;
        switch (type) {
            case "empty":
                tmplayers = new EmptyGetLayers();
                break;
            case "full":
                tmplayers = new FullGetLayers();
                break;
            case "faulty":
                tmplayers = new FaultyGetLayers();
                break;
            default:
                throw new IllegalArgumentException(
                    String.format("Unsupported type: %s", type)
                );
        }
        return tmplayers;
    }
}
