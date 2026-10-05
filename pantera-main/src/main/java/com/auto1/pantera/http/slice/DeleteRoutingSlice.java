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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Sends a repository's {@code DELETE} requests to a Pantera-level delete
 * (the generic repository-path delete, or a proxy's cache eviction) instead
 * of the adapter, except the paths the adapter deletes natively (npm
 * unpublish and dist-tags, the helm chart API, conda token revocation):
 * those, and every other method, reach the adapter unchanged.
 *
 * <p>Sits inside {@code TrimPathSlice}, so the paths it matches are
 * repository-relative.</p>
 *
 * @since 2.2.10
 */
public final class DeleteRoutingSlice implements Slice {

    /**
     * Adapter.
     */
    private final Slice origin;

    /**
     * Pantera-level delete.
     */
    private final Slice delete;

    /**
     * Repository-relative paths whose {@code DELETE} goes to {@link #delete}.
     */
    private final Predicate<String> handled;

    /**
     * Ctor: every {@code DELETE} goes to the Pantera-level delete.
     * @param origin Adapter
     * @param delete Pantera-level delete
     */
    public DeleteRoutingSlice(final Slice origin, final Slice delete) {
        this(origin, delete, path -> true);
    }

    /**
     * Ctor.
     * @param origin Adapter
     * @param delete Pantera-level delete
     * @param handled Repository-relative paths whose {@code DELETE} goes to
     *  the Pantera-level delete; any other path stays with the adapter
     */
    public DeleteRoutingSlice(
        final Slice origin, final Slice delete, final Predicate<String> handled
    ) {
        this.origin = origin;
        this.delete = delete;
        this.handled = handled;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> res;
        if (line.method() == RqMethod.DELETE && this.handled.test(line.uri().getPath())) {
            res = this.delete.response(line, headers, body);
        } else {
            res = this.origin.response(line, headers, body);
        }
        return res;
    }
}
