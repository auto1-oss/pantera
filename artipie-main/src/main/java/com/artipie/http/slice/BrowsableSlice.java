/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Accept;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Slice wrapper that adds directory browsing capability.
 * When a GET request is made with Accept: text/html header and the target is a directory,
 * it returns an HTML directory listing. Otherwise, it delegates to the wrapped slice.
 *
 * @since 1.18.18
 */
public final class BrowsableSlice implements Slice {

    /**
     * Pattern to detect HTML accept header.
     */
    private static final Pattern HTML_ACCEPT = Pattern.compile(".*text/html.*", Pattern.CASE_INSENSITIVE);

    /**
     * Known file extensions for artifacts and metadata files.
     * This is more reliable than regex patterns for detecting files vs directories.
     */
    private static final java.util.Set<String> FILE_EXTENSIONS = java.util.Set.of(
        // Java artifacts
        "jar", "war", "ear", "aar", "apk",
        // Maven/Gradle metadata
        "pom", "xml", "gradle", "properties", "module",
        // Checksums
        "md5", "sha1", "sha256", "sha512", "asc", "sig",
        // Archives
        "zip", "tar", "gz", "bz2", "xz", "tgz", "tbz2",
        // Source files
        "java", "kt", "scala", "groovy", "class",
        // Documentation
        "txt", "md", "pdf", "html", "htm",
        // Data formats
        "json", "yaml", "yml", "toml", "ini", "conf",
        // Python
        "whl", "egg", "py", "pyc", "pyd",
        // Ruby
        "gem", "rb",
        // Node
        "js", "ts", "mjs", "cjs", "node",
        // .NET
        "dll", "exe", "nupkg", "snupkg",
        // Go
        "mod", "sum",
        // Rust
        "crate", "rlib",
        // Docker
        "dockerfile",
        // Other
        "log", "lock", "gpg"
    );

    /**
     * Storage for checking if keys exist.
     */
    private final Storage storage;

    /**
     * Wrapped slice.
     */
    private final Slice origin;

    /**
     * Browse slice for directory listings.
     */
    private final BrowseSlice browse;

    /**
     * Ctor.
     *
     * @param origin Origin slice to wrap
     * @param storage Storage for directory listings
     */
    public BrowsableSlice(final Slice origin, final Storage storage) {
        this.origin = origin;
        this.storage = storage;
        this.browse = new BrowseSlice(storage);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // SECURITY FIX: Always delegate to origin slice first for authentication
        // The origin slice will handle auth, then we intercept successful responses
        // to provide directory browsing for HTML requests
        
        // Only intercept GET requests with HTML accept header
        if (line.method() == RqMethod.GET && this.acceptsHtml(headers)) {
            final String path = line.uri().getPath();
            
            // Fast path: If it's a known file extension, serve directly (file download)
            if (this.hasKnownFileExtension(path)) {
                return this.origin.response(line, headers, body);
            }
            
            // For paths that might be directories, delegate to origin for auth check
            // If origin returns 404, try directory listing (still through origin)
            return this.origin.response(line, headers, body)
                .thenCompose(resp -> {
                    // If origin succeeded, return its response (file download or existing handler)
                    if (resp.status().success()) {
                        return CompletableFuture.completedFuture(resp);
                    }
                    
                    // If origin returned 404, it might be a directory
                    // Try directory listing, but ONLY if path looks like a directory
                    if (resp.status().code() == 404 && this.looksLikeDirectory(path)) {
                        // Generate directory listing (auth already passed via origin)
                        return this.browse.response(line, headers, body)
                            .thenCompose(browseResp -> {
                                if (browseResp.status().success()) {
                                    return CompletableFuture.completedFuture(browseResp);
                                }
                                // If browsing also failed, return original 404
                                return CompletableFuture.completedFuture(resp);
                            });
                    }
                    
                    // For other errors (401, 403, etc.), return as-is
                    return CompletableFuture.completedFuture(resp);
                });
        }
        
        // For all other requests, delegate to origin
        return this.origin.response(line, headers, body);
    }
    
    /**
     * Check if path looks like a directory (no file extension or ends with /).
     *
     * @param path Request path
     * @return True if looks like directory
     */
    private boolean looksLikeDirectory(final String path) {
        return path.endsWith("/") || !this.hasAnyExtension(path);
    }

    /**
     * Check if request accepts HTML.
     *
     * @param headers Request headers
     * @return True if accepts HTML
     */
    private boolean acceptsHtml(final Headers headers) {
        return headers.stream()
            .filter(h -> Accept.NAME.equalsIgnoreCase(h.getKey()))
            .anyMatch(h -> HTML_ACCEPT.matcher(h.getValue()).matches());
    }

    /**
     * Check if path has a known file extension (fast path optimization).
     *
     * @param path Request path
     * @return True if has known file extension
     */
    private boolean hasKnownFileExtension(final String path) {
        // Remove trailing slash and get last segment
        final String normalized = path.replaceAll("/+$", "");
        final int lastSlash = normalized.lastIndexOf('/');
        final String lastSegment = lastSlash >= 0 
            ? normalized.substring(lastSlash + 1) 
            : normalized;
        
        // Get extension (everything after last dot)
        final int lastDot = lastSegment.lastIndexOf('.');
        if (lastDot < 0 || lastDot == lastSegment.length() - 1) {
            return false; // No extension or ends with dot
        }
        
        final String extension = lastSegment.substring(lastDot + 1).toLowerCase();
        return FILE_EXTENSIONS.contains(extension);
    }

    /**
     * Check if path has ANY file extension (known or unknown).
     * This is used for file repositories that can host arbitrary file types.
     *
     * @param path Request path
     * @return True if has any extension
     */
    private boolean hasAnyExtension(final String path) {
        // Remove trailing slash and get last segment
        final String normalized = path.replaceAll("/+$", "");
        final int lastSlash = normalized.lastIndexOf('/');
        final String lastSegment = lastSlash >= 0 
            ? normalized.substring(lastSlash + 1) 
            : normalized;
        
        // Check if it has a dot (extension)
        final int lastDot = lastSegment.lastIndexOf('.');
        if (lastDot < 0 || lastDot == lastSegment.length() - 1) {
            return false; // No extension or ends with dot
        }
        
        // Has extension - check it's not a version number (e.g., 1.3.0)
        final String extension = lastSegment.substring(lastDot + 1);
        // If extension is all digits, it's likely a version number, not a file
        return !extension.matches("\\d+");
    }
}
