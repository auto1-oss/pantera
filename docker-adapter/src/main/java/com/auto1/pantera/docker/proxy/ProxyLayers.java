/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.asto.BlobSource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Proxy implementation of {@link Layers}.
 *
 * @since 0.3
 */
public final class ProxyLayers implements Layers {

    /**
     * Remote repository.
     */
    private final Slice remote;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Ctor.
     *
     * @param remote Remote repository.
     * @param name Repository name.
     */
    public ProxyLayers(Slice remote, String name) {
        this.remote = remote;
        this.name = name;
    }

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
        String blobPath = String.format("/v2/%s/blobs/%s", this.name, digest.string());
        return new ResponseSink<>(
            this.remote.response(new RequestLine(RqMethod.HEAD, blobPath), Headers.EMPTY, Content.EMPTY),
            response -> {
                // CRITICAL FIX: Consume response body to prevent Vert.x request leak
                // HEAD requests may have bodies that need to be consumed for proper completion
                return response.body().asBytesFuture().thenApply(ignored -> {
                    final Optional<Blob> result;
                    if (response.status() == RsStatus.OK) {
                        result = Optional.of(
                            new ProxyBlob(
                                this.remote,
                                this.name,
                                digest,
                                new ContentLength(response.headers()).longValue()
                            )
                        );
                    } else if (response.status() == RsStatus.NOT_FOUND) {
                        result = Optional.empty();
                    } else {
                        throw new IllegalArgumentException("Unexpected status: " + response.status());
                    }
                    return result;
                });
            }
        ).result();
    }
}
