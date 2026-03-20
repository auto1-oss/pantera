/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.http.PanteraHttpException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import java.util.concurrent.CompletableFuture;

/**
 * Proxy implementation of {@link Blob}.
 */
public final class ProxyBlob implements Blob {

    /**
     * Remote repository.
     */
    private final Slice remote;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Blob digest.
     */
    private final Digest digest;

    /**
     * Blob size.
     */
    private final long blobSize;

    /**
     * @param remote Remote repository.
     * @param name Repository name.
     * @param digest Blob digest.
     * @param size Blob size.
     */
    public ProxyBlob(Slice remote, String name, Digest digest, long size) {
        this.remote = remote;
        this.name = name;
        this.digest = digest;
        this.blobSize = size;
    }

    @Override
    public Digest digest() {
        return this.digest;
    }

    @Override
    public CompletableFuture<Long> size() {
        return CompletableFuture.completedFuture(this.blobSize);
    }

    @Override
    public CompletableFuture<Content> content() {
        String blobPath = String.format("/v2/%s/blobs/%s", this.name, this.digest.string());
        return this.remote
            .response(new RequestLine(RqMethod.GET, blobPath), Headers.EMPTY, Content.EMPTY)
            .thenCompose(response -> {
                if (response.status() == RsStatus.OK) {
                    Content res = response.headers()
                        .find(ContentLength.NAME)
                        .stream()
                        .findFirst()
                        .map(h -> Long.parseLong(h.getValue()))
                        .map(val -> (Content) new Content.From(val, response.body()))
                        .orElseGet(response::body);
                    return CompletableFuture.completedFuture(res);
                }
                // CRITICAL: Consume body even on error to prevent request leak
                return response.body().asBytesFuture().thenCompose(
                    ignored -> CompletableFuture.failedFuture(
                        new PanteraHttpException(response.status(), "Unexpected status: " + response.status())
                    )
                );
            });
    }
}
