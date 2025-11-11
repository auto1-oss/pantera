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
import com.jcabi.log.Logger;
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
            Logger.info(this, "Composer group %s: merging packages.json from %d members", this.group, this.members.size());
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
            Logger.info(this, "Path: %s, X-FullPath: %s, X-Original-Path: %s, Using: %s", 
                path, 
                headers.find("X-FullPath").stream().findFirst().map(h -> h.getValue()).orElse("none"),
                headers.find("X-Original-Path").stream().findFirst().map(h -> h.getValue()).orElse("none"),
                originalPath
            );
            // Extract base path for metadata-url (everything before /packages.json)
            final String basePath = extractBasePath(originalPath);
            Logger.info(this, "Base path for metadata-url: %s", basePath);
            return mergePackagesJson(line, headers, body, basePath);
        }

        // For other requests (individual packages), try members sequentially
        Logger.debug(this, "Composer group %s: trying members for %s", this.group, path);
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
                    
                    Logger.debug(this, "Fetching packages.json from member %s", member);
                    
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
                                        
                                        Logger.debug(
                                            this,
                                            "Member %s returned packages.json with %d packages/providers",
                                            member,
                                            packageCount
                                        );
                                        return json;
                                    } catch (Exception e) {
                                        Logger.warn(
                                            this,
                                            "Failed to parse packages.json from member %s: %s",
                                            member,
                                            e.getMessage()
                                        );
                                        return Json.createObjectBuilder().build();
                                    }
                                });
                        } else {
                            Logger.debug(
                                this,
                                "Member %s returned status %s for packages.json",
                                member,
                                resp.status()
                            );
                            return CompletableFuture.completedFuture(
                                Json.createObjectBuilder().build()
                            );
                        }
                    })
                    .exceptionally(ex -> {
                        Logger.warn(
                            this,
                            "Error fetching packages.json from member %s: %s",
                            member,
                            ex.getMessage()
                        );
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
                        Logger.debug(
                            this,
                            "Member returned Satis format with %d providers",
                            providers.size()
                        );
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
                    Logger.info(
                        this,
                        "Using Satis format for group %s: %d providers",
                        this.group,
                        providersBuilder.build().size()
                    );
                } else {
                    // Use traditional format
                    merged.add("metadata-url", basePath + "/p2/%package%.json");
                    merged.add("packages", packagesBuilder.build());
                    Logger.info(
                        this,
                        "Using traditional format for group %s: %d packages",
                        this.group,
                        packagesBuilder.build().size()
                    );
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
            Logger.debug(this, "No member in group %s could serve %s", this.group, line.uri().getPath());
            return ResponseBuilder.notFound().completedFuture();
        }

        final String member = this.members.get(index);
        final Slice memberSlice = this.resolver.slice(new Key.From(member), this.port, 0);
        final RequestLine rewritten = rewritePath(line, member);
        final Headers sanitized = dropFullPathHeader(headers);

        Logger.debug(this, "Group %s trying member %s for %s", this.group, member, line.uri().getPath());

        return memberSlice.response(rewritten, sanitized, Content.EMPTY)
            .thenCompose(resp -> {
                Logger.debug(
                    this,
                    "Member %s responded with %s for %s",
                    member,
                    resp.status(),
                    line.uri().getPath()
                );
                
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
