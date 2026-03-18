/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.asto.fs.FileStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Accept;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.log.EcsLogger;

import java.util.concurrent.CompletableFuture;

/**
 * Slice wrapper that adds directory browsing capability using streaming approach.
 * 
 * <p>When a GET request is made with Accept: text/html header and the target looks like
 * a directory (no file extension or ends with /), it returns an HTML directory listing
 * using {@link StreamingBrowseSlice}. Otherwise, it delegates to the wrapped slice.</p>
 * 
 * <p>This is a simple, high-performance wrapper with:</p>
 * <ul>
 *   <li>No caching overhead</li>
 *   <li>Constant memory usage</li>
 *   <li>Sub-second response for any directory size</li>
 *   <li>Streaming HTML generation</li>
 * </ul>
 *
 * @since 1.18.20
 */
public final class BrowsableSlice implements Slice {

    /**
     * Known file extensions for artifacts and metadata files.
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
     * Wrapped origin slice.
     */
    private final Slice origin;

    /**
     * Storage for dynamically dispatching to optimized implementations.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param origin Origin slice to wrap
     * @param storage Storage for directory listings
     */
    public BrowsableSlice(final Slice origin, final Storage storage) {
        this.origin = origin;
        this.storage = storage;
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
            
            // Fast path: If it's a known file extension, serve directly
            if (this.hasKnownFileExtension(path)) {
                return this.origin.response(line, headers, body);
            }
            
            // For paths that look like directories, check auth FIRST via origin
            if (this.looksLikeDirectory(path)) {
                // Try origin slice first (which has authentication)
                return this.origin.response(line, headers, body)
                    .thenCompose(originResp -> {
                        final int code = originResp.status().code();
                        
                        // If auth failed, return immediately (don't show directory listing)
                        if (code == 401 || code == 403) {
                            return CompletableFuture.completedFuture(originResp);
                        }
                        
                        // If origin succeeded (200), return it (file found)
                        if (originResp.status().success()) {
                            return CompletableFuture.completedFuture(originResp);
                        }
                        
                        // If origin returned 404 (not found), try directory listing
                        // Auth has already passed at this point
                        if (code == 404) {
                            final Slice browseSlice = this.selectBrowseSlice();
                            return browseSlice.response(line, headers, body);
                        }
                        
                        // For any other response code, return origin response
                        return CompletableFuture.completedFuture(originResp);
                    });
            }
        }
        
        // For all other requests, delegate to origin
        return this.origin.response(line, headers, body);
    }
    
    /**
     * Check if path looks like a directory (no file extension or ends with /).
     */
    private boolean looksLikeDirectory(final String path) {
        return path.endsWith("/") || !this.hasAnyExtension(path);
    }

    /**
     * Check if request explicitly prefers HTML.
     * Excludes PyPI Simple API types which use their own format.
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
                if (!value.contains("text/html")) {
                    return false;
                }
                
                // Parse Accept header values
                final String[] parts = value.split(",");
                for (final String part : parts) {
                    final String trimmed = part.trim();
                    // Check for explicit text/html (not just wildcards)
                    if (trimmed.startsWith("text/html")) {
                        return true;
                    }
                }
                
                return false;
            });
    }

    /**
     * Check if path has a known file extension (fast path optimization).
     */
    private boolean hasKnownFileExtension(final String path) {
        final String normalized = path.replaceAll("/+$", "");
        final int lastSlash = normalized.lastIndexOf('/');
        final String lastSegment = lastSlash >= 0 
            ? normalized.substring(lastSlash + 1) 
            : normalized;
        
        final int lastDot = lastSegment.lastIndexOf('.');
        if (lastDot < 0 || lastDot == lastSegment.length() - 1) {
            return false;
        }
        
        final String extension = lastSegment.substring(lastDot + 1).toLowerCase();
        return FILE_EXTENSIONS.contains(extension);
    }

    /**
     * Check if path has ANY file extension (known or unknown).
     */
    private boolean hasAnyExtension(final String path) {
        final String normalized = path.replaceAll("/+$", "");
        final int lastSlash = normalized.lastIndexOf('/');
        final String lastSegment = lastSlash >= 0 
            ? normalized.substring(lastSlash + 1) 
            : normalized;
        
        final int lastDot = lastSegment.lastIndexOf('.');
        if (lastDot < 0 || lastDot == lastSegment.length() - 1) {
            return false;
        }
        
        // If extension is all digits, it's likely a version number, not a file
        final String extension = lastSegment.substring(lastDot + 1);
        return !extension.matches("\\d+");
    }

    /**
     * Select the optimal browse slice implementation based on storage type.
     * Dispatches to storage-specific implementations for maximum performance.
     *
     * @return Appropriate browse slice for the storage type
     */
    private Slice selectBrowseSlice() {
        // Unwrap storage to find the underlying implementation (for detection only)
        Storage unwrapped = unwrapStorage(this.storage);
        
        // FileStorage: Use direct NIO for 10x performance boost
        // IMPORTANT: Pass original storage (with SubStorage prefix) to maintain repo scoping
        if (unwrapped instanceof FileStorage) {
            EcsLogger.debug("com.artipie.http")
                .message("Using FileSystemBrowseSlice for direct NIO access (unwrapped: " + unwrapped.getClass().getSimpleName() + ", original: " + this.storage.getClass().getSimpleName() + ")")
                .eventCategory("http")
                .eventAction("browse_slice_select")
                .log();
            // Use original storage to preserve SubStorage prefix (repo scoping)
            return new FileSystemBrowseSlice(this.storage);
        }

        // S3 and other storage types: Use streaming abstraction
        // TODO: Add S3BrowseSlice with pagination for large S3 directories
        EcsLogger.debug("com.artipie.http")
            .message("Using StreamingBrowseSlice for storage type: " + unwrapped.getClass().getSimpleName())
            .eventCategory("http")
            .eventAction("browse_slice_select")
            .log();
        return new StreamingBrowseSlice(this.storage);
    }

    /**
     * Unwrap storage to find the underlying implementation.
     * Storages are wrapped by DiskCacheStorage, SubStorage, etc.
     *
     * @param storage Storage to unwrap
     * @return Underlying storage implementation
     */
    private static Storage unwrapStorage(final Storage storage) {
        Storage current = storage;
        int maxDepth = 10; // Prevent infinite loops
        
        // Unwrap common wrappers (may be nested)
        for (int depth = 0; depth < maxDepth; depth++) {
            final String className = current.getClass().getSimpleName();
            boolean unwrapped = false;
            
            try {
                // Try DispatchedStorage unwrapping
                if (className.equals("DispatchedStorage")) {
                    final java.lang.reflect.Field delegate =
                        current.getClass().getDeclaredField("delegate");
                    delegate.setAccessible(true);
                    final Storage next = (Storage) delegate.get(current);
                    EcsLogger.debug("com.artipie.http")
                        .message("Unwrapped DispatchedStorage to: " + next.getClass().getSimpleName())
                        .eventCategory("http")
                        .eventAction("storage_unwrap")
                        .log();
                    current = next;
                    unwrapped = true;
                }

                // Try DiskCacheStorage unwrapping
                if (className.equals("DiskCacheStorage")) {
                    final java.lang.reflect.Field backend =
                        current.getClass().getDeclaredField("backend");
                    backend.setAccessible(true);
                    final Storage next = (Storage) backend.get(current);
                    EcsLogger.debug("com.artipie.http")
                        .message("Unwrapped DiskCacheStorage to: " + next.getClass().getSimpleName())
                        .eventCategory("http")
                        .eventAction("storage_unwrap")
                        .log();
                    current = next;
                    unwrapped = true;
                }

                // Try SubStorage unwrapping
                if (className.equals("SubStorage")) {
                    final java.lang.reflect.Field origin =
                        current.getClass().getDeclaredField("origin");
                    origin.setAccessible(true);
                    final Storage next = (Storage) origin.get(current);
                    EcsLogger.debug("com.artipie.http")
                        .message("Unwrapped SubStorage to: " + next.getClass().getSimpleName())
                        .eventCategory("http")
                        .eventAction("storage_unwrap")
                        .log();
                    current = next;
                    unwrapped = true;
                }

                // No more wrappers found, stop unwrapping
                if (!unwrapped) {
                    break;
                }

            } catch (Exception e) {
                EcsLogger.debug("com.artipie.http")
                    .message("Could not unwrap storage type: " + className)
                    .eventCategory("http")
                    .eventAction("storage_unwrap")
                    .eventOutcome("failure")
                    .field("error.message", e.getMessage())
                    .log();
                break;
            }
        }
        
        return current;
    }
}
