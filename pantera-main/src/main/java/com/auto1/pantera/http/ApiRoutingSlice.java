/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.settings.repo.Repositories;
import org.apache.http.client.utils.URIBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice decorator which redirects API requests to repository format paths.
 * Supports multiple access patterns for different repository types.
 * <p>
 * Supported patterns for all repositories:
 * <ul>
 *   <li>/{repo_name}</li>
 *   <li>/{prefix}/{repo_name}</li>
 *   <li>/api/{repo_name}</li>
 *   <li>/{prefix}/api/{repo_name}</li>
 *   <li>/api/{repo_type}/{repo_name}</li>
 *   <li>/{prefix}/api/{repo_type}/{repo_name}</li>
 * </ul>
 * <p>
 * When the first segment after /api/ matches a known repo type (e.g., "npm"),
 * the second segment is checked against the repository registry. If it is a
 * known repo name, the repo_type interpretation is used. Otherwise, the first
 * segment is treated as the repo name (repo_name interpretation).
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
     * Predicate to check if a repository name exists.
     */
    private final Predicate<String> repoExists;

    /**
     * Constructor with repository registry for disambiguation.
     * @param origin Origin slice
     * @param repos Repository registry
     */
    public ApiRoutingSlice(final Slice origin, final Repositories repos) {
        this.origin = origin;
        this.repoExists = name -> repos.config(name).isPresent();
    }

    /**
     * Constructor without repository registry (backward compatible).
     * Falls back to assuming segments[1] is always a repo name
     * when first segment matches a repo type.
     * @param origin Origin slice
     */
    public ApiRoutingSlice(final Slice origin) {
        this.origin = origin;
        this.repoExists = name -> true;
    }

    /**
     * Constructor with custom predicate (for testing).
     * @param origin Origin slice
     * @param repoExists Predicate to check if a repo name exists
     */
    ApiRoutingSlice(final Slice origin, final Predicate<String> repoExists) {
        this.origin = origin;
        this.repoExists = repoExists;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        final String path = line.uri().getPath();
        final Matcher matcher = PTN_API.matcher(path);

        if (matcher.matches()) {
            final String prefix = matcher.group(1);    // e.g., "/test_prefix" or null
            final String apiPath = matcher.group(2);   // Everything after /api/

            // Split the path into segments
            final String[] segments = apiPath.split("/", 3);
            if (segments.length < 1) {
                return this.origin.response(line, headers, body);
            }

            // Check if first segment is a repo_type.
            // Ambiguity: /api/npm/X — is "npm" the repo_type or repo_name?
            // Resolved by checking if X is a known repository name. If yes,
            // use repo_type interpretation. If not, treat first segment as
            // the repo_name.
            final String firstSegment = segments[0];
            if (REPO_TYPE_MAPPING.containsKey(firstSegment)
                && segments.length >= 2
                && this.repoExists.test(segments[1])) {
                // Pattern: /api/{repo_type}/{repo_name}[/rest]
                final String repoName = segments[1];
                final String rest = segments.length > 2 ? "/" + segments[2] : "";
                return this.rewrite(line, headers, body, path, prefix, repoName, rest);
            } else {
                // Pattern: /api/{repo_name}[/rest]
                final String repoName = firstSegment;
                final String rest = segments.length > 1
                    ? "/" + apiPath.substring(repoName.length() + 1) : "";
                return this.rewrite(line, headers, body, path, prefix, repoName, rest);
            }
        }

        return this.origin.response(line, headers, body);
    }

    /**
     * Rewrite the request path and forward to origin.
     */
    private CompletableFuture<Response> rewrite(
        final RequestLine line, final Headers headers, final Content body,
        final String originalPath, final String prefix,
        final String repoName, final String rest
    ) {
        final String newPath = (prefix != null ? prefix : "") + "/" + repoName + rest;
        final Headers newHeaders = headers.copy();
        newHeaders.add("X-Original-Path", originalPath);
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
