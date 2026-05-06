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
 * <p>Handles Composer-specific group behavior:
 * <ul>
 *   <li>{@code packages.json}: sequential-first across members; the first
 *       member that returns 200 wins. Its body is parsed, the
 *       {@code metadata-url} / {@code providers-url} fields are rewritten
 *       to point at the group's own basePath (so p2 fetches stay inside
 *       pantera), and the result is served.</li>
 *   <li>{@code /p2/...}: sequential trial across members.</li>
 *   <li>Everything else: delegated to {@code GroupResolver}.</li>
 * </ul>
 *
 * <p><b>v2.2.0 BREAKING:</b> previously this slice fanned out to ALL members
 * in parallel and merged every member's {@code packages.json} into a
 * union. That was symmetric with the maven-metadata.xml merge in
 * {@code MavenGroupSlice}; both are now sequential-first for the same
 * reasons (member namespaces are typically disjoint, JFrog/Nexus virtual
 * repos behave the same way, the union added per-request upstream
 * amplification with no real benefit).
 *
 * @since 1.0
 */
public final class ComposerGroupSlice implements Slice {

    /**
     * Delegate group slice for non-packages.json requests.
     * Uses the standard GroupResolver with artifact index, proxy awareness,
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
     * @param delegate Delegate group slice (GroupResolver with index/proxy support)
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
        this(delegate, resolver, group, members, port, globalPrefix,
            com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService.INSTANCE,
            "php-group");
    }

    /** Cooldown metadata service applied to merged packages.json. */
    private final com.auto1.pantera.cooldown.metadata.CooldownMetadataService cooldownMetadata;

    /** Repo type for cooldown lookups (php-group / file-group). */
    private final String repoType;

    /**
     * Ctor with cooldown metadata filtering on the merged response.
     *
     * <p>Composer resolves package versions from {@code packages.json} (or the
     * {@code provider-includes} indirection used by Satis). Without filtering
     * the merged document, a hosted member can re-introduce versions that the
     * proxy member's per-request filter had stripped, and the client would
     * resolve to a version it cannot subsequently download (403 from the
     * artifact gate). Symmetric to MavenGroupSlice's filter.
     *
     * @param delegate Delegate group slice
     * @param resolver Slice resolver
     * @param group Group repository name
     * @param members List of member repository names
     * @param port Server port
     * @param globalPrefix Global URL prefix
     * @param cooldownMetadata Cooldown metadata filter service (NOOP to disable)
     * @param repoType Repo type ("php-group" or "file-group")
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public ComposerGroupSlice(
        final Slice delegate,
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final String globalPrefix,
        final com.auto1.pantera.cooldown.metadata.CooldownMetadataService cooldownMetadata,
        final String repoType
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
        this.cooldownMetadata = cooldownMetadata;
        this.repoType = repoType;
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
        if (path.endsWith("/packages.json") || "/packages.json".equals(path)) {
            return mergePackagesJson(line, headers, body);
        }

        // For p2 metadata requests, try each member directly.
        // The artifact index cannot match p2 paths (it stores package names,
        // not filesystem paths), so the delegate GroupResolver would skip local
        // members and return 404.
        if (path.contains("/p2/")) {
            return tryMembersForP2(line, headers, body);
        }

        // For other requests (tarballs, artifacts), delegate to GroupResolver
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
     * Try members in declared order for {@code packages.json}; the first
     * member that returns 200 wins. The winning JSON is rewritten so
     * {@code metadata-url} / {@code providers-url} point at the group's own
     * basePath — without that, Composer would follow the upstream's URL and
     * bypass pantera entirely (cooldown filter + cache + auth).
     *
     * <p>v2.2.0 sequential-first replacement for the previous fanout+merge —
     * see class javadoc.
     *
     * @param line Request line
     * @param headers Headers
     * @param body Body
     * @return Single-member response, packages-url rewritten to group basePath
     */
    private CompletableFuture<Response> mergePackagesJson(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // CRITICAL: Consume original body to prevent OneTimePublisher errors.
        // GET requests have empty bodies, but Content is still reference-counted.
        return body.asBytesFuture().thenCompose(ignored ->
            tryMembersForPackagesJson(line, headers, 0)
        );
    }

    /**
     * Sequentially try {@code members[idx]} for the {@code packages.json}
     * path; on 200 parse + rewrite + return, on non-OK drain the body and
     * recurse to the next member. Falls through to 404 once the index walks
     * past the end.
     */
    private CompletableFuture<Response> tryMembersForPackagesJson(
        final RequestLine line,
        final Headers headers,
        final int idx
    ) {
        if (idx >= this.members.size()) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("No member returned packages.json")
                .eventCategory("web")
                .eventAction("packages_fetch")
                .eventOutcome("failure")
                .field("repository.name", this.group)
                .log();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        }
        final String member = this.members.get(idx);
        final Slice memberSlice = this.resolver.slice(new Key.From(member), this.port, 0);
        final RequestLine rewritten = rewritePath(line, member);
        final Headers sanitized = dropFullPathHeader(headers);

        EcsLogger.debug("com.auto1.pantera.composer")
            .message("Trying member for packages.json: " + member)
            .eventCategory("web")
            .eventAction("packages_fetch")
            .field("repository.name", this.group)
            .log();

        return memberSlice.response(rewritten, sanitized, Content.EMPTY)
            .thenCompose(resp -> {
                if (resp.status() == RsStatus.OK) {
                    return resp.body().asBytesFuture()
                        .thenApply(bytes -> rewritePackagesJson(member, bytes));
                }
                EcsLogger.debug("com.auto1.pantera.composer")
                    .message("Member '" + member + "' non-OK; trying next")
                    .eventCategory("web")
                    .eventAction("packages_fetch")
                    .eventOutcome("failure")
                    .field("http.response.status_code", resp.status().code())
                    .field("repository.name", this.group)
                    .log();
                // Drain non-OK body to release upstream connection, then recurse.
                return resp.body().asBytesFuture()
                    .thenCompose(drained -> tryMembersForPackagesJson(line, headers, idx + 1));
            })
            .exceptionally(ex -> {
                EcsLogger.warn("com.auto1.pantera.composer")
                    .message("Member '" + member + "' threw; trying next")
                    .eventCategory("web")
                    .eventAction("packages_fetch")
                    .eventOutcome("failure")
                    .field("error.message", ex.getMessage())
                    .field("repository.name", this.group)
                    .log();
                return null;
            })
            .thenCompose(resp -> {
                if (resp != null) {
                    return CompletableFuture.completedFuture(resp);
                }
                // Exception path collapsed to null — try next.
                return tryMembersForPackagesJson(line, headers, idx + 1);
            });
    }

    /**
     * Parse the winning member's {@code packages.json} bytes, rewrite
     * {@code metadata-url} / {@code providers-url} (and group-level
     * {@code providers}) to point at the group's own basePath, and emit the
     * resulting bytes as a 200. This rewrite is essential — without it,
     * Composer would follow the upstream's metadata-url and bypass pantera
     * entirely (cooldown filter + cache + auth).
     *
     * <p>UID injection on package versions is preserved: Composer v1 uses
     * the {@code uid} field for cache invalidation; we inject a stable UUID
     * if the upstream omitted it.</p>
     */
    private Response rewritePackagesJson(final String member, final byte[] bytes) {
        final JsonObject json;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            json = reader.readObject();
        } catch (Exception e) {
            EcsLogger.warn("com.auto1.pantera.composer")
                .message("Failed to parse packages.json from member: " + member)
                .eventCategory("web")
                .eventAction("packages_parse")
                .eventOutcome("failure")
                .field("error.message", e.getMessage())
                .field("repository.name", this.group)
                .log();
            return ResponseBuilder.notFound().build();
        }

        final JsonObjectBuilder out = Json.createObjectBuilder();
        // Copy every field except the URL fields we rewrite.
        json.forEach((key, value) -> {
            if (!"packages".equals(key)
                && !"metadata-url".equals(key)
                && !"providers-url".equals(key)
                && !"providers".equals(key)) {
                out.add(key, value);
            }
        });

        final boolean hasSatisFormat = json.containsKey("providers")
            && json.get("providers") instanceof JsonObject;

        if (hasSatisFormat) {
            // Satis format: copy provider table verbatim; rewrite providers-url
            // to the group basePath so client p2 lookups land back at this
            // group slice (which routes through tryMembersForP2).
            out.add("packages", Json.createObjectBuilder());
            out.add("providers-url", this.basePath + "/p2/%package%.json");
            out.add("providers", json.getJsonObject("providers"));
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Sequential winner (Satis format)")
                .eventCategory("web")
                .eventAction("packages_fetch")
                .eventOutcome("success")
                .field("repository.name", this.group)
                .field("repository.member", member)
                .log();
        } else {
            // Traditional format: copy packages, inject uid where missing,
            // rewrite metadata-url to group basePath. Composer v1 needs an
            // absolute path, not relative.
            final JsonObjectBuilder packagesBuilder = Json.createObjectBuilder();
            if (json.containsKey("packages")
                && json.get("packages") instanceof JsonObject) {
                final JsonObject packages = json.getJsonObject("packages");
                packages.forEach((name, versionsObj) -> {
                    if (!(versionsObj instanceof JsonObject)) {
                        return;
                    }
                    final JsonObject versions = (JsonObject) versionsObj;
                    final JsonObjectBuilder pkgWithUids = Json.createObjectBuilder();
                    versions.forEach((version, versionData) -> {
                        if (!(versionData instanceof JsonObject)) {
                            return;
                        }
                        final JsonObject versionObj = (JsonObject) versionData;
                        final JsonObjectBuilder versionWithUid =
                            Json.createObjectBuilder(versionObj);
                        if (!versionObj.containsKey("uid")) {
                            versionWithUid.add("uid", UUID.randomUUID().toString());
                        }
                        pkgWithUids.add(version, versionWithUid.build());
                    });
                    packagesBuilder.add(name, pkgWithUids.build());
                });
            }
            out.add("metadata-url", this.basePath + "/p2/%package%.json");
            out.add("packages", packagesBuilder.build());
            EcsLogger.debug("com.auto1.pantera.composer")
                .message("Sequential winner (traditional format)")
                .eventCategory("web")
                .eventAction("packages_fetch")
                .eventOutcome("success")
                .field("repository.name", this.group)
                .field("repository.member", member)
                .log();
        }

        final byte[] outBytes = out.build().toString().getBytes(StandardCharsets.UTF_8);
        return ResponseBuilder.ok()
            .header("Content-Type", "application/json")
            .body(outBytes)
            .build();
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
