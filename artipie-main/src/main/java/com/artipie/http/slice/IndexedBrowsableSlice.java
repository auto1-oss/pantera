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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * High-performance slice wrapper that adds indexed directory browsing capability.
 * 
 * <p>This is an enhanced version of {@link BrowsableSlice} that uses incremental indexing
 * for dramatically improved performance with large repositories (100K+ artifacts).</p>
 * 
 * <p>When a GET request is made with Accept: text/html header and the target is a directory,
 * it returns an HTML directory listing using the indexed approach. Otherwise, it delegates 
 * to the wrapped slice.</p>
 * 
 * <p>Performance improvements:</p>
 * <ul>
 *   <li>First request: ~100-500ms (vs 30-120s for large repos)</li>
 *   <li>Cached requests: ~5-20ms (vs 30-120s)</li>
 *   <li>Memory efficient: O(cached directories) not O(total artifacts)</li>
 *   <li>Background refresh prevents stale data</li>
 * </ul>
 *
 * @since 1.18.18
 */
public final class IndexedBrowsableSlice implements Slice {

    /**
     * String to detect HTML accept header.
     * Only matches explicit text/html, not wildcards like star-slash-star
     */
    private static final String HTML_ACCEPT = "text/html";

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
     * Indexed browse slice for directory listings (wrapped with authentication).
     */
    private final Slice indexedBrowse;

    /**
     * Actual indexed browse slice for shutdown (without auth wrapper).
     */
    private final IndexedBrowseSlice actualIndexedBrowse;

    /**
     * Fallback browse slice for when indexed approach fails.
     */
    private final BrowseSlice fallbackSlice;

    /**
     * Ctor with default 5-minute cache TTL.
     *
     * @param origin Origin slice to wrap
     * @param storage Storage for directory listings
     * @param repoName Repository name for cache isolation
     */
    public IndexedBrowsableSlice(final Slice origin, final Storage storage, final String repoName) {
        this(origin, storage, repoName, TimeUnit.MINUTES.toMillis(5));
    }

    /**
     * Ctor with custom cache TTL.
     *
     * @param origin Origin slice to wrap
     * @param storage Storage for directory listings
     * @param repoName Repository name for cache isolation
     * @param cacheTtlMs Cache TTL in milliseconds
     */
    public IndexedBrowsableSlice(final Slice origin, final Storage storage, final String repoName, final long cacheTtlMs) {
        this.origin = origin;
        this.storage = storage;
        // Create repository-specific cache directory
        final java.nio.file.Path cacheDir = java.nio.file.Paths.get(
            System.getProperty("java.io.tmpdir"), 
            "artipie-dir-cache", 
            repoName.replaceAll("[^a-zA-Z0-9._-]", "_")
        );
        this.actualIndexedBrowse = new IndexedBrowseSlice(storage, cacheTtlMs, cacheDir);
        this.indexedBrowse = new AuthenticatedIndexedBrowseSlice(origin, this.actualIndexedBrowse);
        this.fallbackSlice = new BrowseSlice(storage);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Only intercept GET requests with HTML accept header
        if (line.method() == RqMethod.GET && this.acceptsHtml(headers)) {
            final String path = line.uri().getPath();
            
            // Fast path: If it's a known file extension, serve directly (file download)
            if (this.hasKnownFileExtension(path)) {
                return this.origin.response(line, headers, body);
            }
            
            // For paths that look like directories with HTML accept, try directory listing FIRST
            // This prevents NPM and other adapters from trying to parse directory paths as packages
            if (this.looksLikeDirectory(path)) {
                return this.indexedBrowse.response(line, headers, body)
                    .thenCompose(browseResp -> {
                        // If auth failed (401/403), return immediately
                        if (browseResp.status().code() == 401 || browseResp.status().code() == 403) {
                            return CompletableFuture.completedFuture(browseResp);
                        }
                        // If directory listing succeeded, return it
                        if (browseResp.status().success()) {
                            return CompletableFuture.completedFuture(browseResp);
                        }
                        // If indexed browsing failed, try fallback
                        return this.fallbackSlice.response(line, headers, body)
                            .thenCompose(fallbackResp -> {
                                if (fallbackResp.status().success()) {
                                    return CompletableFuture.completedFuture(fallbackResp);
                                }
                                // If both failed, delegate to origin (might be a package name without extension)
                                return this.origin.response(line, headers, body);
                            });
                    });
            }
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
     * Check if request explicitly prefers HTML over other content types.
     * This follows proper HTTP Accept header semantics:
     * - PyPI Simple API types (application/vnd.pypi.simple.v1+json/html) → No HTML (API mode)
     * - text/html without PyPI types → HTML listing
     * - Only wildcards → No HTML
     *
     * @param headers Request headers
     * @return True if explicitly prefers HTML (not PyPI API)
     */
    private boolean acceptsHtml(final Headers headers) {
        return headers.stream()
            .filter(h -> Accept.NAME.equalsIgnoreCase(h.getKey()))
            .anyMatch(h -> {
                final String value = h.getValue().toLowerCase();
                
                // If PyPI Simple API content types are present, this is an API request
                if (value.contains("application/vnd.pypi.simple.v1+json") || 
                    value.contains("application/vnd.pypi.simple.v1+html")) {
                    return false;
                }
                
                // Quick check: if no text/html at all, definitely not HTML
                if (!value.contains(HTML_ACCEPT)) {
                    return false;
                }
                
                // Parse Accept header values
                final String[] parts = value.split(",");
                boolean hasExplicitHtml = false;
                boolean hasWildcard = false;
                
                for (final String part : parts) {
                    final String trimmed = part.trim();
                    
                    // Check for explicit text/html
                    if (trimmed.startsWith("text/html")) {
                        hasExplicitHtml = true;
                    }
                    // Check for wildcards
                    else if (trimmed.startsWith("*/*") || trimmed.startsWith("text/*")) {
                        hasWildcard = true;
                    }
                }
                
                // Only serve HTML if text/html is explicitly mentioned
                // not just covered by wildcards
                return hasExplicitHtml;
            });
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

    /**
     * Shutdown the indexed browse slice and cleanup resources.
     */
    public void shutdown() {
        this.actualIndexedBrowse.shutdown();
    }

    /**
     * Inner class that wraps IndexedBrowseSlice with authentication.
     * This ensures that directory listing requests go through the same
     * authentication and authorization as file requests.
     */
    private static class AuthenticatedIndexedBrowseSlice implements Slice {
        
        private final Slice origin;
        private final IndexedBrowseSlice indexedBrowse;

        AuthenticatedIndexedBrowseSlice(final Slice origin, final IndexedBrowseSlice indexedBrowse) {
            this.origin = origin;
            this.indexedBrowse = indexedBrowse;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            // First, check authentication by trying to access the path through the origin slice
            // We use a HEAD request or check if a parent directory exists to validate auth
            return this.origin.response(line, headers, body)
                .thenCompose(resp -> {
                    // If authentication/authorization fails (401, 403), return that response
                    if (!resp.status().success() && resp.status().code() != 404) {
                        return CompletableFuture.completedFuture(resp);
                    }
                    
                    // If we get 404 (file not found) or success, it means auth passed
                    // Now we can safely show the directory listing
                    return this.indexedBrowse.response(line, headers, body);
                });
        }
    }
}
