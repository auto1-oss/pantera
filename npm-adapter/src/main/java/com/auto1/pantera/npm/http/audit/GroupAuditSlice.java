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
package com.auto1.pantera.npm.http.audit;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;

/**
 * Group audit slice - aggregates audit results from all member repositories.
 *
 * <p>For npm groups with both local and proxy members, this slice ensures that
 * vulnerability data from upstream registries is preserved when combining
 * results from multiple backends.
 *
 * <p>It queries all members in parallel, waits for their responses, and merges
 * the vulnerability data from all sources.
 *
 * @since 1.1
 */
public final class GroupAuditSlice implements Slice {

    /**
     * Timeout for audit queries in seconds.
     */
    private static final long AUDIT_TIMEOUT_SECONDS = 30;

    /**
     * Named member with its slice.
     */
    private static final class NamedMember {
        private final String name;
        private final Slice slice;

        NamedMember(final String name, final Slice slice) {
            this.name = name;
            this.slice = slice;
        }
    }

    /**
     * Member repository slices with their names (local + proxy repos).
     */
    private final List<NamedMember> members;

    /**
     * Constructor.
     * @param memberNames List of member repository names
     * @param memberSlices List of member repository slices (same order as names)
     */
    public GroupAuditSlice(final List<String> memberNames, final List<Slice> memberSlices) {
        if (memberNames.size() != memberSlices.size()) {
            throw new IllegalArgumentException(
                "Member names and slices must have same size: " +
                memberNames.size() + " vs " + memberSlices.size()
            );
        }
        this.members = new java.util.ArrayList<>(memberNames.size());
        for (int i = 0; i < memberNames.size(); i++) {
            this.members.add(new NamedMember(memberNames.get(i), memberSlices.get(i)));
        }
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final long startTime = System.currentTimeMillis();

        // Log member names for observability
        final String memberList = this.members.stream()
            .map(m -> m.name)
            .reduce((a, b) -> a + ", " + b)
            .orElse("(none)");

        EcsLogger.info("com.auto1.pantera.npm")
            .message(String.format("NPM Group Audit - START - querying %d members: [%s]", this.members.size(), memberList))
            .eventCategory("repository")
            .eventAction("group_audit_start")
            .field("url.path", line.uri().getPath())
            .log();

        // Read the body once (it will be reused for all members)
        return body.asBytesFuture().thenCompose(bodyBytes -> {
            // Query all members in parallel so vulnerabilities from proxy members are included
            final List<CompletableFuture<JsonObject>> auditResults = new java.util.ArrayList<>();
            for (final NamedMember member : this.members) {
                // Rewrite path to include member prefix.
                // Member slices are wrapped in TrimPathSlice which expects /member-name/path
                final RequestLine rewritten = rewritePath(line, member.name);
                auditResults.add(this.queryMember(member, rewritten, headers, bodyBytes));
            }

            // Wait for ALL members to respond (not just the first success!)
            // Use thenComposeAsync to avoid blocking Vert.x event loop thread
            return CompletableFuture.allOf(auditResults.toArray(new CompletableFuture[0]))
                .orTimeout(AUDIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApplyAsync(v -> {
                    // Merge all vulnerability results using a Map to deduplicate
                    // This runs on ForkJoinPool.commonPool(), not Vert.x event loop
                    final Map<String, JsonValue> merged = new HashMap<>();
                    int emptyCount = 0;
                    int nonEmptyCount = 0;
                    int idx = 0;

                    for (CompletableFuture<JsonObject> future : auditResults) {
                        // Safe: allOf() guarantees all futures are complete
                        // getNow() is non-blocking when future is complete
                        final JsonObject result = future.getNow(Json.createObjectBuilder().build());
                        final String memberName = idx < this.members.size() ? this.members.get(idx).name : "unknown";
                        if (result.isEmpty()) {
                            emptyCount++;
                        } else {
                            nonEmptyCount++;
                            // Merge entries - later entries with same key overwrite
                            result.forEach(merged::put);
                        }
                        idx++;
                    }

                    final long duration = System.currentTimeMillis() - startTime;
                    if (merged.isEmpty()) {
                        EcsLogger.info("com.auto1.pantera.npm")
                            .message(String.format("NPM Group Audit - no vulnerabilities found: %d empty, %d non-empty members", emptyCount, nonEmptyCount))
                            .eventCategory("repository")
                            .eventAction("group_audit")
                            .eventOutcome("success")
                            .duration(duration)
                            .log();
                        return ResponseBuilder.ok()
                            .jsonBody(Json.createObjectBuilder().build())
                            .build();
                    }

                    EcsLogger.info("com.auto1.pantera.npm")
                        .message(String.format("NPM Group Audit - found %d vulnerabilities: %d empty, %d non-empty members", merged.size(), emptyCount, nonEmptyCount))
                        .eventCategory("repository")
                        .eventAction("group_audit")
                        .eventOutcome("success")
                        .duration(duration)
                        .log();

                    // Build merged response
                    final var builder = Json.createObjectBuilder();
                    merged.forEach(builder::add);

                    return ResponseBuilder.ok()
                        .jsonBody(builder.build())
                        .build();
                })
                .exceptionally(err -> {
                    final long duration = System.currentTimeMillis() - startTime;
                    EcsLogger.error("com.auto1.pantera.npm")
                        .message("NPM Group Audit failed")
                        .eventCategory("repository")
                        .eventAction("group_audit")
                        .eventOutcome("failure")
                        .duration(duration)
                        .error(err)
                        .log();
                    // On timeout/error, return empty (no vulnerabilities) rather than fail
                    return ResponseBuilder.ok()
                        .jsonBody(Json.createObjectBuilder().build())
                        .build();
                });
        });
    }

    /**
     * Query a member repository for audit results.
     * @param member Named member with slice
     * @param line Request line (already rewritten with member prefix)
     * @param headers Request headers
     * @param bodyBytes Request body bytes
     * @return Future with audit results (never fails - returns empty on error)
     */
    private CompletableFuture<JsonObject> queryMember(
        final NamedMember member,
        final RequestLine line,
        final Headers headers,
        final byte[] bodyBytes
    ) {
        EcsLogger.debug("com.auto1.pantera.npm")
            .message("Querying member for audit: " + member.name)
            .eventCategory("repository")
            .eventAction("group_audit")
            .field("member.name", member.name)
            .field("url.path", line.uri().getPath())
            .log();

        return member.slice.response(
            line,
            dropFullPathHeader(headers),
            new Content.From(bodyBytes)
        ).thenCompose(response -> {
            // Check status - only parse successful responses
            if (!response.status().success()) {
                EcsLogger.debug("com.auto1.pantera.npm")
                    .message("Member audit returned non-success status")
                    .eventCategory("repository")
                    .eventAction("group_audit")
                    .field("member.name", member.name)
                    .field("http.response.status_code", response.status().code())
                    .log();
                // Drain body and return empty
                return response.body().asBytesFuture()
                    .thenApply(ignored -> Json.createObjectBuilder().build());
            }
            return response.body().asBytesFuture()
                .thenApply(bytes -> {
                    try {
                        final String json = new String(bytes, StandardCharsets.UTF_8);
                        if (json.isBlank() || json.equals("{}")) {
                            EcsLogger.debug("com.auto1.pantera.npm")
                                .message("Member returned empty audit response")
                                .eventCategory("repository")
                                .eventAction("group_audit")
                                .field("member.name", member.name)
                                .log();
                            return Json.createObjectBuilder().build();
                        }
                        try (JsonReader reader = Json.createReader(new StringReader(json))) {
                            final JsonObject result = reader.readObject();
                            EcsLogger.debug("com.auto1.pantera.npm")
                                .message(String.format("Member returned audit data with %d entries", result.size()))
                                .eventCategory("repository")
                                .eventAction("group_audit")
                                .field("member.name", member.name)
                                .log();
                            return result;
                        }
                    } catch (Exception e) {
                        EcsLogger.warn("com.auto1.pantera.npm")
                            .message("Failed to parse audit response from member: " + member.name)
                            .eventCategory("repository")
                            .eventAction("group_audit")
                            .field("member.name", member.name)
                            .error(e)
                            .log();
                        return Json.createObjectBuilder().build();
                    }
                });
        }).exceptionally(err -> {
            EcsLogger.warn("com.auto1.pantera.npm")
                .message("Member audit query failed: " + member.name)
                .eventCategory("repository")
                .eventAction("group_audit")
                .field("member.name", member.name)
                .error(err)
                .log();
            return Json.createObjectBuilder().build();
        });
    }

    /**
     * Rewrite request path to include member repository name.
     *
     * <p>Member slices are wrapped in TrimPathSlice which expects paths with member prefix.
     * Example: /-/npm/v1/security/advisories/bulk → /npm-proxy/-/npm/v1/security/advisories/bulk
     *
     * @param original Original request line
     * @param memberName Member repository name to prefix
     * @return Rewritten request line with member prefix
     */
    private static RequestLine rewritePath(final RequestLine original, final String memberName) {
        final URI uri = original.uri();
        final String raw = uri.getRawPath();
        final String base = raw.startsWith("/") ? raw : "/" + raw;
        final String prefix = "/" + memberName + "/";

        // Avoid double-prefixing
        final String path = base.startsWith(prefix) ? base : ("/" + memberName + base);

        final StringBuilder full = new StringBuilder(path);
        if (uri.getRawQuery() != null) {
            full.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            full.append('#').append(uri.getRawFragment());
        }

        return new RequestLine(
            original.method().value(),
            full.toString(),
            original.version()
        );
    }

    /**
     * Drop X-FullPath header from headers (internal header, not needed for members).
     */
    private static Headers dropFullPathHeader(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(h -> !h.getKey().equalsIgnoreCase("X-FullPath"))
                .toList()
        );
    }
}
