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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.api.v1.ArtifactDeletion;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.concurrent.CompletableFuture;

/**
 * Search-index cascade of an adapter's native {@code DELETE} (pypi, debian,
 * rpm keep their own delete handlers, which update the format's metadata
 * but not Pantera's search index): when the adapter answers a {@code DELETE}
 * with a {@code 2xx}, the index rows of the deleted storage path and the
 * tree view's cached metadata of it are removed
 * ({@link ArtifactDeletion#afterNativeDelete}).
 *
 * <p>The index rows of these formats carry the uploaded file's storage key
 * as {@code path_prefix}, so the delete's storage path matches them.
 * The cascade is best-effort: a failure is logged and never changes the
 * adapter's response. Every other request, and a non-2xx answer, passes
 * through untouched.</p>
 *
 * <p>Must sit where the adapter sees the request: inside
 * {@code TrimPathSlice} and inside any alias strip, so the path here is the
 * repository-relative storage path the adapter deleted.</p>
 *
 * @since 2.2.10
 */
public final class NativeDeleteCascadeSlice implements Slice {

    /**
     * Adapter.
     */
    private final Slice origin;

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Shared artifact delete.
     */
    private final ArtifactDeletion deletion;

    /**
     * Ctor.
     * @param origin Adapter
     * @param repo Repository name
     * @param deletion Shared artifact delete
     */
    public NativeDeleteCascadeSlice(
        final Slice origin, final String repo, final ArtifactDeletion deletion
    ) {
        this.origin = origin;
        this.repo = repo;
        this.deletion = deletion;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> res;
        if (line.method() == RqMethod.DELETE) {
            final String path = RepoDeleteSlice.clean(line.uri().getPath());
            res = this.origin.response(line, headers, body).thenCompose(
                rsp -> {
                    final CompletableFuture<Response> out;
                    if (rsp.status().success() && !path.isEmpty()
                        && !this.deletion.unsafe(path)) {
                        out = this.deletion.afterNativeDelete(this.repo, path, headers)
                            .handle((rows, err) -> rsp);
                    } else {
                        out = CompletableFuture.completedFuture(rsp);
                    }
                    return out;
                }
            );
        } else {
            res = this.origin.response(line, headers, body);
        }
        return res;
    }
}
