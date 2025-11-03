/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.artipie.npm.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.npm.PackageNameFromUrl;
import com.artipie.npm.PerVersionLayout;
import com.artipie.npm.Tarballs;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Download package endpoint. Return package metadata, all tarball links will be rewritten
 * based on requested URL.
 */
public final class DownloadPackageSlice implements Slice {

    private final URL base;
    private final Storage storage;

    /**
     * @param base Base URL
     * @param storage Abstract storage
     */
    public DownloadPackageSlice(final URL base, final Storage storage) {
        this.base = base;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String pkg = new PackageNameFromUrl(line).value();
        
        // Guard: If this is a browser request (Accept: text/html) for directory browsing,
        // return 404 to let IndexedBrowsableSlice handle it
        // NPM CLI always sends Accept: application/json or application/vnd.npm.install-v1+json
        // BUT: Only reject if it looks like a directory (no file extension)
        final boolean isHtmlRequest = headers.stream()
            .anyMatch(h -> "Accept".equalsIgnoreCase(h.getKey()) 
                && h.getValue().contains("text/html"));
        
        if (isHtmlRequest && !this.hasFileExtension(line.uri().getPath())) {
            // This is a directory browsing request, let IndexedBrowsableSlice handle it
            // Consume request body to prevent Vert.x request leak, then return 404
            return body.asBytesFuture().thenApply(ignored ->
                ResponseBuilder.notFound().build()
            );
        }
        
        // Additional guard: If package name is empty, return 404
        // This prevents "Empty parts are not allowed" error
        if (pkg == null || pkg.isEmpty() || pkg.equals("/") || pkg.trim().isEmpty()) {
            // Consume request body to prevent Vert.x request leak, then return 404
            return body.asBytesFuture().thenApply(ignored ->
                ResponseBuilder.notFound().build()
            );
        }
        
        final Key packageKey = new Key.From(pkg);
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        
        // Check if per-version layout exists
        return layout.hasVersions(packageKey).thenCompose(hasVersions -> {
            if (hasVersions) {
                // Use per-version layout - generate meta.json dynamically
                return layout.generateMetaJson(packageKey)
                    .thenApply(metaJson -> {
                        final byte[] bytes = metaJson.toString().getBytes(StandardCharsets.UTF_8);
                        final Content content = new Content.From(bytes);
                        return new Tarballs(content, this.base).value();
                    })
                    .thenApply(content -> 
                        ResponseBuilder.ok()
                            .header(new Header("Content-Type", "application/json"))
                            .body(content)
                            .build()
                    );
            } else {
                // Fall back to old layout - read existing meta.json
                final Key metaKey = new Key.From(pkg, "meta.json");
                return this.storage.exists(metaKey).thenCompose(exists -> {
                    if (exists) {
                        return this.storage.value(metaKey)
                            .thenApply(content -> new Tarballs(content, this.base).value())
                            .thenApply(content -> 
                                ResponseBuilder.ok()
                                    .header(new Header("Content-Type", "application/json"))
                                    .body(content)
                                    .build()
                            );
                    } else {
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.notFound().build()
                        );
                    }
                });
            }
        }).toCompletableFuture();
    }
    
    /**
     * Check if path has a file extension (contains a dot in the last segment).
     * @param path Request path
     * @return True if has file extension
     */
    private boolean hasFileExtension(final String path) {
        // Get last segment after final slash
        final int lastSlash = path.lastIndexOf('/');
        final String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        
        // Check if it has a dot (extension)
        final int lastDot = lastSegment.lastIndexOf('.');
        return lastDot > 0 && lastDot < lastSegment.length() - 1;
    }
}
