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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Directory browsing slice that renders HTML directory listings.
 * This slice provides native repository browsing for file-based repositories.
 * It preserves the full request path (including repository name) in all generated links
 * to ensure proper navigation without 404 errors.
 *
 * @since 1.18.18
 */
public final class BrowseSlice implements Slice {

    /**
     * Storage to browse.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param storage Storage to browse
     */
    public BrowseSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Get the full original path from X-FullPath header if available
        // This header is set by TrimPathSlice and contains the complete path including repo name
        final String fullPath = new RqHeaders(headers, "X-FullPath")
            .stream()
            .findFirst()
            .orElse(line.uri().getPath());

        // Extract the artifact path (path after repository name)
        final String artifactPath = line.uri().getPath();
        
        // Convert to storage key
        final Key key = new Key.From(artifactPath.replaceAll("^/+", ""));

        // Use hierarchical listing with delimiter for scalability
        return this.storage.list(key, "/").thenApply(
            result -> {
                final String html = this.renderHtml(
                    fullPath, 
                    artifactPath, 
                    result.files(),
                    result.directories()
                );
                return ResponseBuilder.ok()
                    .header(ContentType.mime("text/html; charset=utf-8"))
                    .body(html.getBytes(StandardCharsets.UTF_8))
                    .build();
            }
        ).exceptionally(
            throwable -> ResponseBuilder.internalError()
                .textBody("Failed to list directory: " + throwable.getMessage())
                .build()
        );
    }

    /**
     * Render HTML directory listing.
     *
     * @param fullPath Full request path including repository name
     * @param artifactPath Artifact path (after repository name)
     * @param files Collection of file keys at this level
     * @param directories Collection of directory keys at this level
     * @return HTML string
     */
    private String renderHtml(
        final String fullPath,
        final String artifactPath,
        final Collection<Key> files,
        final Collection<Key> directories
    ) {
        final StringBuilder html = new StringBuilder(2048);

        // Determine the base path for links (the full path up to current directory)
        final String basePath = fullPath.endsWith("/") ? fullPath : fullPath + "/";
        final String displayPath = artifactPath.isEmpty() || "/".equals(artifactPath) 
            ? "/" 
            : artifactPath;

        html.append("<html>\n");
        html.append("<head>\n");
        html.append("  <title>Index of ").append(escapeHtml(displayPath)).append("</title>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("<h1>Index of ").append(escapeHtml(displayPath)).append("</h1>\n");
        html.append("<hr>\n");
        html.append("<pre>\n");

        // Add parent directory link if not at root
        if (!artifactPath.isEmpty() && !"/".equals(artifactPath)) {
            final String parentPath = this.getParentPath(fullPath);
            html.append("<a href=\"")
                .append(escapeHtml(parentPath))
                .append("\">../</a>\n");
        }

        // Render directories first (already sorted by Storage implementation)
        for (final Key dir : directories) {
            final String dirStr = dir.string();
            // Extract just the directory name (last segment before trailing /)
            String name = dirStr.replaceAll("/+$", ""); // Remove trailing slashes
            final int lastSlash = name.lastIndexOf('/');
            if (lastSlash >= 0) {
                name = name.substring(lastSlash + 1);
            }
            
            if (!name.isEmpty()) {
                final String href = basePath + name + "/";
                html.append("<a href=\"").append(escapeHtml(href)).append("\">")
                    .append(escapeHtml(name)).append("/</a>\n");
            }
        }
        
        // Render files (already sorted by Storage implementation)
        for (final Key file : files) {
            final String fileStr = file.string();
            // Extract just the file name (last segment)
            String name = fileStr;
            final int lastSlash = name.lastIndexOf('/');
            if (lastSlash >= 0) {
                name = name.substring(lastSlash + 1);
            }
            
            if (!name.isEmpty()) {
                final String href = basePath + name;
                html.append("<a href=\"").append(escapeHtml(href)).append("\">")
                    .append(escapeHtml(name)).append("</a>\n");
            }
        }

        html.append("</pre>\n");
        html.append("<hr>\n");
        html.append("</body>\n");
        html.append("</html>\n");

        return html.toString();
    }

    /**
     * Get parent directory path from full path.
     *
     * @param fullPath Full path
     * @return Parent path
     */
    private String getParentPath(final String fullPath) {
        String path = fullPath;
        // Remove trailing slash
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // Find last slash
        final int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            return path.substring(0, lastSlash);
        }
        return "/";
    }


    /**
     * Escape HTML special characters.
     *
     * @param text Text to escape
     * @return Escaped text
     */
    private static String escapeHtml(final String text) {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
