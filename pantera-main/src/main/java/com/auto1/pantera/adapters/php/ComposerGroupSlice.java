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
package com.auto1.pantera.adapters.php;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.group.SliceResolver;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsLogger;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Composer group repository slice.
 * 
 * Handles Composer-specific group behavior:
 * - Merges packages.json from all members
 * - Falls back to sequential member trial for other requests
 *
 * @since 1.0
 */
public final class ComposerGroupSlice implements Slice {

    /**
     * Delegate group slice for non-packages.json requests.
     * Uses the standard GroupSlice with artifact index, proxy awareness,
     * circuit breaker, and error handling.
     */
    private final Slice delegate;

    /**
     * Slice resolver for getting member slices.
     */
    private final SliceResolver resolver;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Member repository names.
     */
    private final List<String> members;

    /**
     * Server port for resolving member slices.
     */
    private final int port;

    /**
     * Base path for metadata-url (e.g. "/test_prefix/php_group").
     * Built from global prefix + group name so Composer can resolve
     * p2 URLs as host-absolute paths.
     */
    private final String basePath;

    /**
     * Constructor with delegate slice for standard group behavior.
     *
     * @param delegate Delegate group slice (GroupSlice with index/proxy support)
     * @param resolver Slice resolver
     * @param group Group repository name
     * @param members List of member repository names
     * @param port Server port
     * @param globalPrefix Global URL prefix (e.g. "test_prefix"), empty string if none
     */
    public ComposerGroupSlice(
        final Slice delegate,
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final String globalPrefix
    ) {
        this.delegate = delegate;
        this.resolver = resolver;
        this.group = group;
        this.members = members;
        this.port = port;
        if (globalPrefix != null && !globalPrefix.isEmpty()) {
            this.basePath = "/" + globalPrefix + "/" + group;
        } else {
            this.basePath = "/" + group;
        }
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String method = line.method().value();
        if (!("GET".equals(method) || "HEAD".equals(method))) {
            return ResponseBuilder.methodNotAllowed().completedFuture();
        }

        final String path = line.uri().getPath();

        // For packages.json, merge responses from all members
        if (path.endsWith("/packages.json") || path.equals("/packages.json")) {
            return mergePackagesJson(line, headers, body);
        }

        // For p2 metadata requests, try each member directly.
        // The artifact index cannot match p2 paths (it stores package names,
        // not filesystem paths), so the delegate GroupSlice would skip local
        // members and return 404.
        if (path.contains("/p2/")) {
            return tryMembersForP2(line, headers, body);
        }

        // For other requests (tarballs, artifacts), delegate to GroupSlice
        // which has artifact index, proxy awareness, circuit breaker, and error handling
        return this.delegate.response(line, headers, body);
    }

    /**
     * Try each member sequentially for p2 metadata requests.
     * Returns the first successful response, or 404 if all members fail.
     */
    private CompletableFuture<Response> tryMembersForP2(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return body.asBytesFuture().thenCompose(requestBytes -> {
            CompletableFuture<Response> chain = CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
            for (final String member : this.members) {
                chain = chain.thenCompose(prev -> {
                    if (prev.status() == RsStatus.OK) {
                        return CompletableFuture.completedFuture(prev);
                    }
                    final Slice memberSlice = this.resolver.slice(
                        new Key.From(member), this.port, 0
                    );
                    final RequestLine rewritten = rewritePath(line, member);
                    final Headers sanitized = dropFullPathHeader(headers);
                    return memberSlice.response(rewritten, sanitized, Content.EMPTY)
                        .thenCompose(resp -> {
                            if (resp.status() == RsStatus.OK) {
                                return CompletableFuture.completedFuture(resp);
                            }
                            // Drain non-OK response body to release upstream connection
                            return resp.body().asBytesFuture()
                                .thenApply(ignored -> prev);
                        })
                        .exceptionally(ex -> prev);
                });
            }
            return chain;
        });
    }

    /**
     * Merge packages.json from all members.
     *
     * @param line Request line
     * @param headers Headers
     * @param body Body
     * @return Merged response
     */
    private CompletableFuture<Response> mergePackagesJson(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // CRITICAL: Consume original body to prevent OneTimePublisher errors
        // GET requests have empty bodies, but Content is still reference-counted
        return body.asBytesFuture().thenCompose(requestBytes -> {
            // Fetch packages.json from all members in parallel with Content.EMPTY
            final List<CompletableFuture<JsonObject>> futures = this.members.stream()
                .map(member -> {
                    final Slice memberSlice = this.resolver.slice(new Key.From(member), this.port, 0);
                    final RequestLine rewritten = rewritePath(line, member);
                    final Headers sanitized = dropFullPathHeader(headers);

                    EcsLogger.debug("com.auto1.pantera.composer")
                        .message("Fetching packages.json from member: " + member)
                        .eventCategory("repository")
                        .eventAction("packages_fetch")
                        .log();

                    return memberSlice.response(rewritten, sanitized, Content.EMPTY)
                    .thenCompose(resp -> {
                        if (resp.status() == RsStatus.OK) {
                            return resp.body().asBytesFuture()
                                .thenApply(bytes -> {
                                    try (JsonReader reader = Json.createReader(
                                        new ByteArrayInputStream(bytes)
                                    )) {
                                        final JsonObject json = reader.readObject();
                                        
                                        // Safely count packages - handle both array (Satis) and object (traditional)
                                        int packageCount = 0;
                                        if (json.containsKey("packages")) {
                                            final var packagesValue = json.get("packages");
                                            if (packagesValue instanceof JsonObject) {
                                                packageCount = ((JsonObject) packagesValue).size();
                                            } else if (json.containsKey("provider-includes")) {
                                                // Satis format - count provider-includes instead
                                                packageCount = json.getJsonObject("provider-includes").size();
                                            }
                                        }

                                        EcsLogger.debug("com.auto1.pantera.composer")
                                            .message("Member '" + member + "' returned packages.json (" + packageCount + " packages)")
                                            .eventCategory("repository")
                                            .eventAction("packages_fetch")
                                            .eventOutcome("success")
                                            .log();
                                        return json;
                                    } catch (Exception e) {
                                        EcsLogger.warn("com.auto1.pantera.composer")
                                            .message("Failed to parse packages.json from member: " + member)
                                            .eventCategory("repository")
                                            .eventAction("packages_parse")
                                            .eventOutcome("failure")
                                            .field("error.message", e.getMessage())
                                            .log();
                                        return Json.createObjectBuilder().build();
                                    }
                                });
                        } else {
                            EcsLogger.debug("com.auto1.pantera.composer")
                                .message("Member '" + member + "' returned non-OK status for packages.json")
                                .eventCategory("repository")
                                .eventAction("packages_fetch")
                                .eventOutcome("failure")
                                .field("http.response.status_code", resp.status().code())
                                .log();
                            // Drain non-OK response body to release upstream connection
                            return resp.body().asBytesFuture().thenApply(ignored ->
                                Json.createObjectBuilder().build()
                            );
                        }
                    })
                    .exceptionally(ex -> {
                        EcsLogger.warn("com.auto1.pantera.composer")
                            .message("Error fetching packages.json from member: " + member)
                            .eventCategory("repository")
                            .eventAction("packages_fetch")
                            .eventOutcome("failure")
                            .field("error.message", ex.getMessage())
                            .log();
                        return Json.createObjectBuilder().build();
                    });
            })
            .toList();

        // Wait for all responses and merge them
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                final JsonObjectBuilder merged = Json.createObjectBuilder();
                final JsonObjectBuilder packagesBuilder = Json.createObjectBuilder();
                final JsonObjectBuilder providersBuilder = Json.createObjectBuilder();
                
                boolean hasSatisFormat = false;

                // Merge all packages from all members
                // Note: futures are already complete at this point (after allOf)
                for (CompletableFuture<JsonObject> future : futures) {
                    final JsonObject json = future.join();  // ✅ Already complete - no blocking!
                    
                    // Handle Satis format (providers)
                    if (json.containsKey("providers")) {
                        hasSatisFormat = true;
                        final JsonObject providers = json.getJsonObject("providers");
                        providers.forEach((key, value) -> {
                            providersBuilder.add(key, value);
                        });
                        EcsLogger.debug("com.auto1.pantera.composer")
                            .message("Member returned Satis format (" + providers.size() + " providers)")
                            .eventCategory("repository")
                            .eventAction("packages_merge")
                            .log();
                    }
                    
                    // Handle traditional format (packages object)
                    if (json.containsKey("packages")) {
                        final var packagesValue = json.get("packages");
                        // Check if it's an object (traditional) or array (Satis empty)
                        if (packagesValue instanceof JsonObject) {
                            final JsonObject packages = (JsonObject) packagesValue;
                            packages.forEach((name, versionsObj) -> {
                                // Add UIDs to each package version
                                final JsonObject versions = (JsonObject) versionsObj;
                                final JsonObjectBuilder pkgWithUids = Json.createObjectBuilder();
                                versions.forEach((version, versionData) -> {
                                    final JsonObject versionObj = (JsonObject) versionData;
                                    final JsonObjectBuilder versionWithUid = Json.createObjectBuilder(versionObj);
                                    if (!versionObj.containsKey("uid")) {
                                        versionWithUid.add("uid", UUID.randomUUID().toString());
                                    }
                                    pkgWithUids.add(version, versionWithUid.build());
                                });
                                packagesBuilder.add(name, pkgWithUids.build());
                            });
                        }
                    }
                    
                    // Preserve other fields from the first non-empty response
                    // But do NOT preserve metadata-url/providers-url - we'll rewrite them
                    if (merged.build().isEmpty()) {
                        json.forEach((key, value) -> {
                            if (!"packages".equals(key) 
                                && !"metadata-url".equals(key) 
                                && !"providers-url".equals(key)
                                && !"providers".equals(key)) {
                                merged.add(key, value);
                            }
                        });
                    }
                }

                // Build appropriate response format
                if (hasSatisFormat) {
                    // Use Satis format for group
                    merged.add("packages", Json.createObjectBuilder()); // Empty object
                    merged.add("providers-url", this.basePath + "/p2/%package%.json");
                    merged.add("providers", providersBuilder.build());
                    EcsLogger.debug("com.auto1.pantera.composer")
                        .message("Using Satis format for group (" + providersBuilder.build().size() + " providers)")
                        .eventCategory("repository")
                        .eventAction("packages_merge")
                        .eventOutcome("success")
                        .field("repository.name", this.group)
                        .log();
                } else {
                    // Use host-absolute metadata-url including global prefix.
                    // Composer (especially v1) needs absolute paths, not relative.
                    merged.add("metadata-url", this.basePath + "/p2/%package%.json");
                    merged.add("packages", packagesBuilder.build());
                    EcsLogger.debug("com.auto1.pantera.composer")
                        .message("Using traditional format for group (" + packagesBuilder.build().size() + " packages)")
                        .eventCategory("repository")
                        .eventAction("packages_merge")
                        .eventOutcome("success")
                        .field("repository.name", this.group)
                        .log();
                }
                
                final JsonObject result = merged.build();

                final String jsonString = result.toString();
                final byte[] bytes = jsonString.getBytes(StandardCharsets.UTF_8);
                
                return ResponseBuilder.ok()
                    .header("Content-Type", "application/json")
                    .body(bytes)
                    .build();
            });
        }); // Close thenCompose lambda for body consumption
    }


    /**
     * Rewrite request line to include member repository name in path.
     *
     * @param original Original request line
     * @param member Member repository name
     * @return Rewritten request line
     */
    private static RequestLine rewritePath(final RequestLine original, final String member) {
        final String path = original.uri().getPath();
        final String newPath = path.startsWith("/") 
            ? "/" + member + path 
            : "/" + member + "/" + path;
        
        final StringBuilder fullUri = new StringBuilder(newPath);
        if (original.uri().getQuery() != null) {
            fullUri.append('?').append(original.uri().getQuery());
        }
        
        return new RequestLine(
            original.method().value(),
            fullUri.toString(),
            original.version()
        );
    }

    /**
     * Drop X-FullPath header to avoid TrimPathSlice recursion issues.
     *
     * @param headers Original headers
     * @return Headers without X-FullPath
     */
    private static Headers dropFullPathHeader(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(h -> !"X-FullPath".equalsIgnoreCase(h.getKey()))
                .toList()
        );
    }

}
