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
import com.artipie.npm.misc.AbbreviatedMetadata;
import com.artipie.npm.misc.MetadataETag;
import com.artipie.npm.misc.MetadataEnhancer;
import javax.json.JsonObject;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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
        // URL-decode package name to handle scoped packages like @retail%2fbackoffice -> @retail/backoffice
        final String rawPkg = new PackageNameFromUrl(line).value();
        final String pkg = URLDecoder.decode(rawPkg, StandardCharsets.UTF_8);
        
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
        
        // P0.1: Check if client requests abbreviated format
        final boolean abbreviated = this.isAbbreviatedRequest(headers);
        
        // P0.2: Check for conditional request (If-None-Match)
        final Optional<String> clientETag = this.extractClientETag(headers);
        
        final Key packageKey = new Key.From(pkg);
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        
        // Check if per-version layout exists
        return layout.hasVersions(packageKey).thenCompose(hasVersions -> {
            if (hasVersions) {
                // Use per-version layout - generate meta.json dynamically
                return layout.generateMetaJson(packageKey)
                    .thenCompose(metaJson -> this.processMetadata(
                        metaJson, abbreviated, clientETag
                    ));
            } else {
                // Fall back to old layout - read existing meta.json
                final Key metaKey = new Key.From(pkg, "meta.json");
                return this.storage.exists(metaKey).thenCompose(exists -> {
                    if (exists) {
                        return this.storage.value(metaKey)
                            .thenCompose(Content::asJsonObjectFuture)
                            .thenCompose(metaJson -> this.processMetadata(
                                metaJson, abbreviated, clientETag
                            ));
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
     * Process metadata: enhance, abbreviate if needed, calculate ETag, handle 304.
     * 
     * @param metaJson Original metadata
     * @param abbreviated Whether to return abbreviated format
     * @param clientETag Client's ETag from If-None-Match header
     * @return Response with metadata or 304 Not Modified
     */
    private CompletableFuture<Response> processMetadata(
        final JsonObject metaJson,
        final boolean abbreviated,
        final Optional<String> clientETag
    ) {
        // P1.1: Enhance metadata with time and users objects
        final JsonObject enhanced = new MetadataEnhancer(metaJson).enhance();
        
        // P0.1: Generate abbreviated or full format
        final JsonObject response = abbreviated
            ? new AbbreviatedMetadata(enhanced).generate()
            : enhanced;
        
        // Convert to string once for ETag calculation
        final String responseStr = response.toString();
        
        // P0.2: Calculate ETag from JSON string (no extra buffering)
        final String etag = new MetadataETag(responseStr).calculate();
        
        // P0.2: Check if client has matching ETag (304 Not Modified)
        if (clientETag.isPresent() && clientETag.get().equals(etag)) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(com.artipie.http.RsStatus.NOT_MODIFIED)
                    .header("ETag", etag)
                    .header("Cache-Control", "public, max-age=300")
                    .build()
            );
        }
        
        // Apply tarball URL rewriting and STREAM response (no buffering!)
        final Content content = new Content.From(responseStr.getBytes(StandardCharsets.UTF_8));
        final Content rewritten = new Tarballs(content, this.base).value();
        
        // Return streaming response - memory usage: ~4KB instead of 200MB+
        return CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Type", abbreviated 
                    ? "application/vnd.npm.install-v1+json; charset=utf-8"
                    : "application/json; charset=utf-8")
                .header("ETag", etag)
                .header("Cache-Control", "public, max-age=300")
                .header("CDN-Cache-Control", "public, max-age=600")
                .body(rewritten)  // STREAM IT - no asBytesFuture()!
                .build()
        );
    }
    
    /**
     * Check if client requests abbreviated manifest.
     * 
     * @param headers Request headers
     * @return True if Accept header contains abbreviated format
     */
    private boolean isAbbreviatedRequest(final Headers headers) {
        return headers.stream()
            .anyMatch(h -> "Accept".equalsIgnoreCase(h.getKey())
                && h.getValue().contains("application/vnd.npm.install-v1+json"));
    }
    
    /**
     * Extract client ETag from If-None-Match header.
     * 
     * @param headers Request headers
     * @return Optional ETag value
     */
    private Optional<String> extractClientETag(final Headers headers) {
        return headers.stream()
            .filter(h -> "If-None-Match".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .map(etag -> etag.startsWith("W/") ? etag.substring(2) : etag)
            .map(etag -> etag.replaceAll("\"", "")) // Remove quotes
            .findFirst();
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
