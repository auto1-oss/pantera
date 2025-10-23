/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.search;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;

/**
 * Group search slice - aggregates search results from all member repositories.
 * 
 * @since 1.1
 */
public final class GroupSearchSlice implements Slice {
    
    /**
     * Query parameter pattern.
     */
    private static final Pattern QUERY_PATTERN = Pattern.compile(
        "text=([^&]+)(?:&size=(\\d+))?(?:&from=(\\d+))?"
    );
    
    /**
     * Default result size.
     */
    private static final int DEFAULT_SIZE = 20;
    
    /**
     * Member repository slices.
     */
    private final List<Slice> members;
    
    /**
     * Constructor.
     * @param members List of member repository slices
     */
    public GroupSearchSlice(final List<Slice> members) {
        this.members = members;
    }
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String query = line.uri().getQuery();
        if (query == null || query.isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.badRequest()
                    .textBody("Search query required")
                    .build()
            );
        }
        
        final Matcher matcher = QUERY_PATTERN.matcher(query);
        if (!matcher.find()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.badRequest()
                    .textBody("Invalid search query")
                    .build()
            );
        }
        
        final int size = matcher.group(2) != null 
            ? Integer.parseInt(matcher.group(2)) 
            : DEFAULT_SIZE;
        final int from = matcher.group(3) != null 
            ? Integer.parseInt(matcher.group(3)) 
            : 0;
        
        // Query all members in parallel
        final List<CompletableFuture<List<PackageMetadata>>> searches = this.members.stream()
            .map(member -> this.searchMember(member, line, headers))
            .collect(Collectors.toList());
        
        // Aggregate results
        return CompletableFuture.allOf(searches.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                // Merge all results, keeping only unique packages (by name)
                final Map<String, PackageMetadata> uniquePackages = new LinkedHashMap<>();
                
                searches.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .forEach(pkg -> uniquePackages.putIfAbsent(pkg.name(), pkg));
                
                // Apply pagination
                final List<PackageMetadata> results = uniquePackages.values().stream()
                    .skip(from)
                    .limit(size)
                    .collect(Collectors.toList());
                
                return this.buildResponse(results);
            })
            .exceptionally(err -> {
                // If aggregation fails, return error
                return ResponseBuilder.internalError()
                    .jsonBody(Json.createObjectBuilder()
                        .add("error", "Failed to aggregate search results")
                        .add("message", err.getMessage())
                        .build())
                    .build();
            });
    }
    
    /**
     * Search a member repository.
     * @param member Member repository slice
     * @param line Request line
     * @param headers Request headers
     * @return Future with search results
     */
    private CompletableFuture<List<PackageMetadata>> searchMember(
        final Slice member,
        final RequestLine line,
        final Headers headers
    ) {
        return member.response(line, headers, Content.EMPTY)
            .thenCompose(response -> response.body().asBytesFuture()
                .thenApply(bytes -> this.parseResults(
                    new String(bytes, StandardCharsets.UTF_8)
                ))
            )
            .exceptionally(err -> {
                // If a member fails, return empty list
                return List.of();
            });
    }
    
    /**
     * Parse search results from JSON response.
     * @param json JSON response
     * @return List of packages
     */
    private List<PackageMetadata> parseResults(final String json) {
        final List<PackageMetadata> results = new ArrayList<>();
        
        try {
            final JsonObject response = Json.createReader(
                new StringReader(json)
            ).readObject();
            
            final JsonArray objects = response.getJsonArray("objects");
            if (objects != null) {
                for (int i = 0; i < objects.size(); i++) {
                    final JsonObject obj = objects.getJsonObject(i);
                    final JsonObject pkg = obj.getJsonObject("package");
                    
                    results.add(new PackageMetadata(
                        pkg.getString("name", ""),
                        pkg.getString("version", ""),
                        pkg.getString("description", ""),
                        this.extractKeywords(pkg)
                    ));
                }
            }
        } catch (Exception e) {
            // If parsing fails, return empty list
        }
        
        return results;
    }
    
    /**
     * Extract keywords from package JSON.
     * @param pkg Package JSON object
     * @return Keywords list
     */
    private List<String> extractKeywords(final JsonObject pkg) {
        final List<String> keywords = new ArrayList<>();
        final JsonArray keywordsArray = pkg.getJsonArray("keywords");
        if (keywordsArray != null) {
            for (int i = 0; i < keywordsArray.size(); i++) {
                keywords.add(keywordsArray.getString(i));
            }
        }
        return keywords;
    }
    
    /**
     * Build search response.
     * @param results Search results
     * @return Response
     */
    private Response buildResponse(final List<PackageMetadata> results) {
        final JsonArrayBuilder objects = Json.createArrayBuilder();
        
        results.forEach(pkg -> {
            final JsonArrayBuilder keywords = Json.createArrayBuilder();
            pkg.keywords().forEach(keywords::add);
            
            objects.add(Json.createObjectBuilder()
                .add("package", Json.createObjectBuilder()
                    .add("name", pkg.name())
                    .add("version", pkg.version())
                    .add("description", pkg.description())
                    .add("keywords", keywords)
                )
                .add("score", Json.createObjectBuilder()
                    .add("final", 1.0)
                    .add("detail", Json.createObjectBuilder()
                        .add("quality", 1.0)
                        .add("popularity", 1.0)
                        .add("maintenance", 1.0)
                    )
                )
                .add("searchScore", 1.0)
            );
        });
        
        return ResponseBuilder.ok()
            .jsonBody(Json.createObjectBuilder()
                .add("objects", objects)
                .add("total", results.size())
                .add("time", System.currentTimeMillis())
                .build())
            .build();
    }
}
