/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.http;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal proxy for npm security audit endpoints.
 * Forwards requests to the upstream registry without caching or transformation.
 */
final class SecurityAuditProxySlice implements Slice {

    /**
     * Upstream slice (e.g., UriClientSlice to remote registry).
     */
    private final Slice remote;

    /**
     * Repository path prefix (repository name) used to trim incoming path.
     */
    private final String repo;

    SecurityAuditProxySlice(final Slice remote, final String repo) {
        this.remote = remote;
        this.repo = repo == null ? "" : repo;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.remote.response(upstream(line), headers, body);
    }

    private RequestLine upstream(final RequestLine original) {
        final URI uri = original.uri();
        String path = uri.getPath();
        if (!this.repo.isEmpty()) {
            final String prefix = String.format("/%s", this.repo);
            if (path.startsWith(prefix + "/")) {
                path = path.substring(prefix.length());
            } else if (path.equals(prefix)) {
                path = "/";
            }
        }
        final StringBuilder target = new StringBuilder(path);
        if (uri.getQuery() != null) {
            target.append('?').append(uri.getQuery());
        }
        if (uri.getFragment() != null) {
            target.append('#').append(uri.getFragment());
        }
        return new RequestLine(original.method(), URI.create(target.toString()), original.version());
    }
}

