/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.rq.RequestLine;
import org.apache.http.client.utils.URIBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice decorator which redirects API requests to repository format paths.
 * Supports multiple access patterns for different repository types.
 * <p>
 * Supported patterns for most repositories:
 * - /{repo_name}
 * - /{prefix}/{repo_name}
 * - /api/{repo_name}
 * - /{prefix}/api/{repo_name}
 * - /api/{repo_type}/{repo_name}
 * - /{prefix}/api/{repo_type}/{repo_name}
 * <p>
 * For gradle, rpm, and maven (limited support):
 * - /{repo_name}
 * - /{prefix}/{repo_name}
 * - /api/{repo_name}
 * - /{prefix}/api/{repo_name}
 */
public final class ApiRoutingSlice implements Slice {

    /**
     * Pattern to match API routes with optional prefix and segments.
     * Captures the full path for manual parsing.
     */
    private static final Pattern PTN_API = Pattern.compile(
        "^(/[^/]+)?/api/(.+)$"
    );

    /**
     * Repository type URL mappings.
     */
    private static final Map<String, String> REPO_TYPE_MAPPING = new HashMap<>();

    /**
     * Repository types with limited support (no repo_type in URL).
     */
    private static final Set<String> LIMITED_SUPPORT = Set.of("gradle", "rpm", "maven");

    static {
        REPO_TYPE_MAPPING.put("conan", "conan");
        REPO_TYPE_MAPPING.put("conda", "conda");
        REPO_TYPE_MAPPING.put("debian", "deb");
        REPO_TYPE_MAPPING.put("docker", "docker");
        REPO_TYPE_MAPPING.put("storage", "file");
        REPO_TYPE_MAPPING.put("gems", "gem");
        REPO_TYPE_MAPPING.put("go", "go");
        REPO_TYPE_MAPPING.put("helm", "helm");
        REPO_TYPE_MAPPING.put("hex", "hexpm");
        REPO_TYPE_MAPPING.put("npm", "npm");
        REPO_TYPE_MAPPING.put("nuget", "nuget");
        REPO_TYPE_MAPPING.put("composer", "php");
        REPO_TYPE_MAPPING.put("pypi", "pypi");
    }

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Decorates slice with API routing.
     * @param origin Origin slice
     */
    public ApiRoutingSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        final String path = line.uri().getPath();
        final Matcher matcher = PTN_API.matcher(path);
        
        if (matcher.matches()) {
            final String prefix = matcher.group(1);    // e.g., "/artifactory" or null
            final String apiPath = matcher.group(2);   // Everything after /api/
            
            // Split the path into segments
            final String[] segments = apiPath.split("/", 3);
            if (segments.length < 1) {
                return this.origin.response(line, headers, body);
            }
            
            // Check if first segment is a repo_type
            final String firstSegment = segments[0];
            if (REPO_TYPE_MAPPING.containsKey(firstSegment) && segments.length >= 2) {
                // Pattern: /api/{repo_type}/{repo_name}[/rest]
                final String repoName = segments[1];
                final String rest = segments.length > 2 ? "/" + segments[2] : "";
                final String newPath = (prefix != null ? prefix : "") + "/" + repoName + rest;
                
                // Preserve original path in header for metadata-url generation
                final Headers newHeaders = headers.copy();
                newHeaders.add("X-Original-Path", path);
                
                return this.origin.response(
                    new RequestLine(
                        line.method().toString(),
                        new URIBuilder(line.uri()).setPath(newPath).toString(),
                        line.version()
                    ),
                    newHeaders,
                    body
                );
            } else {
                // Pattern: /api/{repo_name}[/rest]
                final String repoName = firstSegment;
                final String rest = segments.length > 1 ? "/" + apiPath.substring(repoName.length() + 1) : "";
                final String newPath = (prefix != null ? prefix : "") + "/" + repoName + rest;
                
                // Preserve original path in header for metadata-url generation
                final Headers newHeaders = headers.copy();
                newHeaders.add("X-Original-Path", path);
                
                return this.origin.response(
                    new RequestLine(
                        line.method().toString(),
                        new URIBuilder(line.uri()).setPath(newPath).toString(),
                        line.version()
                    ),
                    newHeaders,
                    body
                );
            }
        }
        
        return this.origin.response(line, headers, body);
    }
}
