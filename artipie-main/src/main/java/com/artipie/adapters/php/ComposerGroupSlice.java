/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.php;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
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
import java.util.function.Function;

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
    private final Function<Key, Slice> resolver;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Member repository names.
     */
    private final List<String> members;

    /**
     * Server port.
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
        final Function<Key, Slice> resolver,
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
            return mergePackagesJson(line, headers, body);
        }

        // For other requests (individual packages), try members sequentially
        Logger.debug(this, "Composer group %s: trying members for %s", this.group, path);
        return tryMembersSequentially(0, line, headers, body);
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
        // Fetch packages.json from all members in parallel
        final List<CompletableFuture<JsonObject>> futures = this.members.stream()
            .map(member -> {
                final Slice memberSlice = this.resolver.apply(new Key.From(member));
                final RequestLine rewritten = rewritePath(line, member);
                final Headers sanitized = dropFullPathHeader(headers);
                
                Logger.debug(this, "Fetching packages.json from member %s", member);
                
                return memberSlice.response(rewritten, sanitized, body)
                    .thenCompose(resp -> {
                        if (resp.status() == RsStatus.OK) {
                            return resp.body().asBytesFuture()
                                .thenApply(bytes -> {
                                    try (JsonReader reader = Json.createReader(
                                        new ByteArrayInputStream(bytes)
                                    )) {
                                        final JsonObject json = reader.readObject();
                                        Logger.debug(
                                            this,
                                            "Member %s returned packages.json with %d packages",
                                            member,
                                            json.containsKey("packages") 
                                                ? json.getJsonObject("packages").size() 
                                                : 0
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

                // Merge all packages from all members
                for (CompletableFuture<JsonObject> future : futures) {
                    final JsonObject json = future.join();
                    if (json.containsKey("packages")) {
                        final JsonObject packages = json.getJsonObject("packages");
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
                    
                    // Preserve metadata-url and other fields from the first non-empty response
                    if (merged.build().isEmpty()) {
                        json.forEach((key, value) -> {
                            if (!"packages".equals(key)) {
                                merged.add(key, value);
                            }
                        });
                    }
                }

                merged.add("packages", packagesBuilder.build());
                final JsonObject result = merged.build();
                
                Logger.info(
                    this,
                    "Merged packages.json for group %s: %d total packages",
                    this.group,
                    result.getJsonObject("packages").size()
                );

                final String jsonString = result.toString();
                final byte[] bytes = jsonString.getBytes(StandardCharsets.UTF_8);
                
                return ResponseBuilder.ok()
                    .header("Content-Type", "application/json")
                    .body(bytes)
                    .build();
            });
    }

    /**
     * Try members sequentially until one returns a non-404 response.
     *
     * @param index Current member index
     * @param line Request line
     * @param headers Headers
     * @param body Body
     * @return Response from first successful member or 404
     */
    private CompletableFuture<Response> tryMembersSequentially(
        final int index,
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (index >= this.members.size()) {
            Logger.debug(this, "No member in group %s could serve %s", this.group, line.uri().getPath());
            return ResponseBuilder.notFound().completedFuture();
        }

        final String member = this.members.get(index);
        final Slice memberSlice = this.resolver.apply(new Key.From(member));
        final RequestLine rewritten = rewritePath(line, member);
        final Headers sanitized = dropFullPathHeader(headers);

        Logger.debug(this, "Group %s trying member %s for %s", this.group, member, line.uri().getPath());

        return memberSlice.response(rewritten, sanitized, body)
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
                    return tryMembersSequentially(index + 1, line, sanitized, body);
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
}
