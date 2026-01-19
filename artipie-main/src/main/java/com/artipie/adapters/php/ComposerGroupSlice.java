/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.php;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.group.SliceResolver;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.log.EcsLogger;
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
     * Constructor.
     *
     * @param resolver Slice resolver
     * @param group Group repository name
     * @param members List of member repository names
     * @param port Server port
     */
    public ComposerGroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port
    ) {
        this.resolver = resolver;
        this.group = group;
        this.members = members;
        this.port = port;
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
            EcsLogger.debug("com.artipie.composer")
                .message("Merging packages.json from " + this.members.size() + " members")
                .eventCategory("repository")
                .eventAction("packages_merge")
                .field("repository.name", this.group)
                .log();
            // Get original path before any routing rewrites
            // Priority: X-Original-Path (from ApiRoutingSlice) > X-FullPath (from TrimPathSlice) > current path
            final String originalPath = headers.find("X-Original-Path").stream()
                .findFirst()
                .map(h -> h.getValue())
                .or(() -> headers.find("X-FullPath").stream()
                    .findFirst()
                    .map(h -> h.getValue())
                )
                .orElse(path);
            EcsLogger.debug("com.artipie.composer")
                .message("Path resolution for packages.json")
                .eventCategory("repository")
                .eventAction("path_resolve")
                .field("url.path", path)
                .field("url.original", originalPath)
                .field("http.request.headers.X-FullPath", headers.find("X-FullPath").stream().findFirst().map(h -> h.getValue()).orElse("none"))
                .field("http.request.headers.X-Original-Path", headers.find("X-Original-Path").stream().findFirst().map(h -> h.getValue()).orElse("none"))
                .log();
            // Extract base path for metadata-url (everything before /packages.json)
            final String basePath = extractBasePath(originalPath);
            EcsLogger.debug("com.artipie.composer")
                .message("Base path for metadata-url")
                .eventCategory("repository")
                .eventAction("path_resolve")
                .field("url.path", basePath)
                .log();
            return mergePackagesJson(line, headers, body, basePath);
        }

        // For other requests (individual packages), try members sequentially
        EcsLogger.debug("com.artipie.composer")
            .message("Trying members for request")
            .eventCategory("repository")
            .eventAction("member_query")
            .field("repository.name", this.group)
            .field("url.path", path)
            .log();
        // CRITICAL: Consume body once before sequential member queries
        return body.asBytesFuture().thenCompose(requestBytes ->
            tryMembersSequentially(0, line, headers)
        );
    }

    /**
     * Merge packages.json from all members.
     *
     * @param line Request line
     * @param headers Headers
     * @param body Body
     * @param basePath Base path for metadata-url (e.g., "/test_prefix/api/composer/php_group" or "/php_group")
     * @return Merged response
     */
    private CompletableFuture<Response> mergePackagesJson(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String basePath
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

                    EcsLogger.debug("com.artipie.composer")
                        .message("Fetching packages.json from member")
                        .eventCategory("repository")
                        .eventAction("packages_fetch")
                        .field("member.name", member)
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

                                        EcsLogger.debug("com.artipie.composer")
                                            .message("Member '" + member + "' returned packages.json (" + packageCount + " packages)")
                                            .eventCategory("repository")
                                            .eventAction("packages_fetch")
                                            .eventOutcome("success")
                                            .field("member.name", member)
                                            .log();
                                        return json;
                                    } catch (Exception e) {
                                        EcsLogger.warn("com.artipie.composer")
                                            .message("Failed to parse packages.json from member")
                                            .eventCategory("repository")
                                            .eventAction("packages_parse")
                                            .eventOutcome("failure")
                                            .field("member.name", member)
                                            .field("error.message", e.getMessage())
                                            .log();
                                        return Json.createObjectBuilder().build();
                                    }
                                });
                        } else {
                            EcsLogger.debug("com.artipie.composer")
                                .message("Member returned non-OK status for packages.json")
                                .eventCategory("repository")
                                .eventAction("packages_fetch")
                                .eventOutcome("failure")
                                .field("member.name", member)
                                .field("http.response.status_code", resp.status().code())
                                .log();
                            return CompletableFuture.completedFuture(
                                Json.createObjectBuilder().build()
                            );
                        }
                    })
                    .exceptionally(ex -> {
                        EcsLogger.warn("com.artipie.composer")
                            .message("Error fetching packages.json from member")
                            .eventCategory("repository")
                            .eventAction("packages_fetch")
                            .eventOutcome("failure")
                            .field("member.name", member)
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
                        EcsLogger.debug("com.artipie.composer")
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
                    merged.add("providers-url", basePath + "/p2/%package%.json");
                    merged.add("providers", providersBuilder.build());
                    EcsLogger.debug("com.artipie.composer")
                        .message("Using Satis format for group (" + providersBuilder.build().size() + " providers)")
                        .eventCategory("repository")
                        .eventAction("packages_merge")
                        .eventOutcome("success")
                        .field("repository.name", this.group)
                        .log();
                } else {
                    // Use traditional format
                    merged.add("metadata-url", basePath + "/p2/%package%.json");
                    merged.add("packages", packagesBuilder.build());
                    EcsLogger.debug("com.artipie.composer")
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
     * Try members sequentially until one returns a non-404 response.
     * Body has already been consumed by caller.
     *
     * @param index Current member index
     * @param line Request line
     * @param headers Headers
     * @return Response from first successful member or 404
     */
    private CompletableFuture<Response> tryMembersSequentially(
        final int index,
        final RequestLine line,
        final Headers headers
    ) {
        if (index >= this.members.size()) {
            EcsLogger.debug("com.artipie.composer")
                .message("No member in group could serve request")
                .eventCategory("repository")
                .eventAction("member_query")
                .eventOutcome("failure")
                .field("repository.name", this.group)
                .field("url.path", line.uri().getPath())
                .log();
            return ResponseBuilder.notFound().completedFuture();
        }

        final String member = this.members.get(index);
        final Slice memberSlice = this.resolver.slice(new Key.From(member), this.port, 0);
        final RequestLine rewritten = rewritePath(line, member);
        final Headers sanitized = dropFullPathHeader(headers);

        EcsLogger.debug("com.artipie.composer")
            .message("Trying member for request")
            .eventCategory("repository")
            .eventAction("member_query")
            .field("repository.name", this.group)
            .field("member.name", member)
            .field("url.path", line.uri().getPath())
            .log();

        return memberSlice.response(rewritten, sanitized, Content.EMPTY)
            .thenCompose(resp -> {
                EcsLogger.debug("com.artipie.composer")
                    .message("Member responded")
                    .eventCategory("repository")
                    .eventAction("member_query")
                    .field("member.name", member)
                    .field("http.response.status_code", resp.status().code())
                    .field("url.path", line.uri().getPath())
                    .log();

                if (resp.status() == RsStatus.NOT_FOUND) {
                    // Try next member
                    return tryMembersSequentially(index + 1, line, sanitized);
                }

                // Return this response (success or error)
                return CompletableFuture.completedFuture(resp);
            });
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

    /**
     * Extract base path from packages.json request path.
     * Examples:
     * - "/packages.json" -> ""
     * - "/php_group/packages.json" -> "/php_group"
     * - "/test_prefix/api/composer/php_group/packages.json" -> "/test_prefix/api/composer/php_group"
     *
     * @param path Full request path
     * @return Base path (without /packages.json suffix)
     */
    private static String extractBasePath(final String path) {
        if (path.endsWith("/packages.json")) {
            return path.substring(0, path.length() - "/packages.json".length());
        }
        if (path.equals("/packages.json")) {
            return "";
        }
        // Fallback: return path as-is
        return path;
    }
}
