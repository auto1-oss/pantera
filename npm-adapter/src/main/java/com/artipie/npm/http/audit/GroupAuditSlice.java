/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.audit;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;

/**
 * Group audit slice - aggregates audit results from all member repositories.
 * 
 * Queries all proxy members in parallel and merges their vulnerability results.
 * 
 * @since 1.1
 */
public final class GroupAuditSlice implements Slice {
    
    /**
     * Member repository slices (should be proxy repos).
     */
    private final List<Slice> members;
    
    /**
     * Constructor.
     * @param members List of member repository slices
     */
    public GroupAuditSlice(final List<Slice> members) {
        this.members = members;
    }
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Read the body once (it will be reused for all members)
        return body.asBytesFuture().thenCompose(bodyBytes -> {
            // Query all members in parallel
            final List<CompletableFuture<JsonObject>> auditResults = this.members.stream()
                .map(member -> this.queryMember(member, line, headers, bodyBytes))
                .collect(Collectors.toList());
            
            // Aggregate results
            return CompletableFuture.allOf(auditResults.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Merge all vulnerability results
                    final Set<String> allVulnerabilities = new HashSet<>();
                    
                    auditResults.stream()
                        .map(CompletableFuture::join)
                        .forEach(result -> {
                            // NPM audit response can have various formats
                            // Most common: { "packageName": { "advisories": {...} } }
                            // Simplified: just collect all unique vulnerability IDs
                            result.forEach((key, value) -> {
                                if (value != null) {
                                    allVulnerabilities.add(key);
                                }
                            });
                        });
                    
                    // If no vulnerabilities found across all members, return empty object
                    if (allVulnerabilities.isEmpty()) {
                        return ResponseBuilder.ok()
                            .jsonBody(Json.createObjectBuilder().build())
                            .build();
                    }
                    
                    // Build merged response with all unique vulnerabilities
                    final var builder = Json.createObjectBuilder();
                    auditResults.stream()
                        .map(CompletableFuture::join)
                        .forEach(result -> result.forEach(builder::add));
                    
                    return ResponseBuilder.ok()
                        .jsonBody(builder.build())
                        .build();
                })
                .exceptionally(err -> {
                    // If aggregation fails, return empty (no vulnerabilities)
                    return ResponseBuilder.ok()
                        .jsonBody(Json.createObjectBuilder().build())
                        .build();
                });
        });
    }
    
    /**
     * Query a member repository for audit results.
     * @param member Member repository slice
     * @param line Request line
     * @param headers Request headers
     * @param bodyBytes Request body bytes
     * @return Future with audit results
     */
    private CompletableFuture<JsonObject> queryMember(
        final Slice member,
        final RequestLine line,
        final Headers headers,
        final byte[] bodyBytes
    ) {
        return member.response(
            line, 
            headers, 
            new Content.From(bodyBytes)
        ).thenCompose(response -> response.body().asBytesFuture()
            .thenApply(bytes -> {
                try {
                    final String json = new String(bytes, StandardCharsets.UTF_8);
                    try (JsonReader reader = Json.createReader(new StringReader(json))) {
                        return reader.readObject();
                    }
                } catch (Exception e) {
                    // If parsing fails, return empty object
                    return Json.createObjectBuilder().build();
                }
            })
        ).exceptionally(err -> {
            // If a member fails, return empty object
            return Json.createObjectBuilder().build();
        });
    }
}
