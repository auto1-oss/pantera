/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.http;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.headers.Header;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Minimal proxy for npm security audit endpoints.
 * Forwards requests to the upstream registry without caching or transformation.
 */
final class SecurityAuditProxySlice implements Slice {

    private static final Logger LOGGER = Logger.getLogger(SecurityAuditProxySlice.class.getName());

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
        final RequestLine upstreamLine = upstream(line);
        
        LOGGER.log(Level.INFO, "NPM Audit Proxy - Original path: {0}", line.uri().getPath());
        LOGGER.log(Level.INFO, "NPM Audit Proxy - Upstream path: {0}", upstreamLine.uri().getPath());
        LOGGER.log(Level.INFO, "NPM Audit Proxy - Repo prefix: {0}", this.repo);
        
        // Build clean headers for upstream - only forward client headers, not internal ones
        // Remove: Host, authorization, artipie_login, X-Real-IP, X-Forwarded-*, Connection
        final Headers clean = new Headers();
        
        // Forward only safe client headers
        for (final Header header : headers) {
            final String name = header.getKey().toLowerCase();
            // Skip internal/proxy headers that Cloudflare rejects
            if (!name.toLowerCase().equals("host") 
                && !name.toLowerCase().equals("authorization")
                && !name.toLowerCase().equals("artipie_login")
                && !name.toLowerCase().startsWith("x-real-")
                && !name.toLowerCase().startsWith("x-forwarded-")
                && !name.toLowerCase().equals("connection")) {
                clean.add(header);
            }
        }
        
        // Ensure User-Agent (Cloudflare bot detection)
        if (clean.values("User-Agent").isEmpty() && clean.values("user-agent").isEmpty()) {
            clean.add("User-Agent", "npm/11.5.1 node/v24.7.0 darwin arm64");
        }
        
        // Ensure Accept header
        if (clean.values("Accept").isEmpty()) {
            clean.add("Accept", "application/json");
        }
        
        // Ensure Content-Type
        if (clean.values("Content-Type").isEmpty() && clean.values("content-type").isEmpty()) {
            clean.add("Content-Type", "application/json");
        }
        
        LOGGER.log(Level.INFO, "NPM Audit Proxy - Forwarding request");
        
        // UriClientSlice will add the correct Host header automatically
        return this.remote.response(upstreamLine, clean, body);
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

