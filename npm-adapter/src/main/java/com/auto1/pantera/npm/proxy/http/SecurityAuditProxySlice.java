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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.net.URI;
import java.util.Locale;
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
        final RequestLine upstreamLine = upstream(line);

        EcsLogger.info("com.auto1.pantera.npm")
            .message("NPM Audit Proxy - Streaming request (repo: " + this.repo + ")")
            .eventCategory("web")
            .eventAction("audit_proxy")
            .field("url.original", line.uri().getPath())
            .field("url.path", upstreamLine.uri().getPath())
            .log();

        // Materialize the body first, then create fresh Content for upstream.
        // The body may have already been partially consumed by logging/routing code,
        // and Content.From(byte[]) creates a one-shot Flowable that can only be read once.
        return body.asBytesFuture().thenCompose(bodyBytes -> {
            // Build clean headers for upstream - only forward safe client headers
            // Filter out ALL internal/proxy headers that upstream registries reject
            final java.util.List<Header> cleanList = new java.util.ArrayList<>();

            for (final Header header : headers) {
                final String name = header.getKey().toLowerCase(Locale.ROOT);
                // Skip ALL internal/proxy headers
                if ("host".equals(name)
                    || "authorization".equals(name)
                    || "pantera_login".equals(name)
                    || name.startsWith("x-real")      // x-real-ip, etc.
                    || name.startsWith("x-forwarded") // x-forwarded-for, x-forwarded-proto
                    || name.startsWith("x-fullpath")  // internal pantera header
                    || name.startsWith("x-original")  // x-original-path
                    || "connection".equals(name)
                    || "transfer-encoding".equals(name) // Will set our own
                    || "content-length".equals(name)) { // Will use actual body length
                    continue;
                }
                cleanList.add(header);
            }

            final Headers clean = new Headers(cleanList);

            // Ensure User-Agent (Cloudflare bot detection)
            if (clean.values("User-Agent").isEmpty() && clean.values("user-agent").isEmpty()) {
                clean.add("User-Agent", "npm/11.5.1 node/v24.7.0 darwin arm64");
            }

            // Ensure Accept header
            if (clean.values("Accept").isEmpty()) {
                clean.add("Accept", "application/json");
            }

            // Ensure Content-Type for POST requests
            if (clean.values("Content-Type").isEmpty() && clean.values("content-type").isEmpty()) {
                clean.add("Content-Type", "application/json");
            }

            // Always use actual body length for Content-Length
            clean.add("Content-Length", String.valueOf(bodyBytes.length));

            // Create fresh Content from materialized bytes
            // UriClientSlice will add the correct Host header automatically
            return this.remote.response(upstreamLine, clean, new Content.From(bodyBytes));
        });
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

