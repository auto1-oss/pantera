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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;

/**
 * Proxy search slice - searches local cache first, then forwards to upstream.
 * 
 * This implementation uses a Slice to forward requests to upstream.
 * For full HTTP client support, inject a UriClientSlice configured
 * with the upstream registry URL.
 * 
 * @since 1.1
 */
public final class ProxySearchSlice implements Slice {
    
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
     * Upstream slice (typically UriClientSlice).
     */
    private final Slice upstream;
    
    /**
     * Local package index.
     */
    private final PackageIndex localIndex;
    
    /**
     * Constructor.
     * @param upstream Upstream slice (e.g., UriClientSlice to npm registry)
     * @param localIndex Local package index
     */
    public ProxySearchSlice(
        final Slice upstream,
        final PackageIndex localIndex
    ) {
        this.upstream = upstream;
        this.localIndex = localIndex;
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
        
        final String text = matcher.group(1);
        final int size = matcher.group(2) != null 
            ? Integer.parseInt(matcher.group(2)) 
            : DEFAULT_SIZE;
        final int from = matcher.group(3) != null 
            ? Integer.parseInt(matcher.group(3)) 
            : 0;
        
        // Search local cache first
        return this.localIndex.search(text, size, from)
            .thenCompose(localResults -> {
                if (localResults.size() >= size) {
                    // Enough local results, no need to query upstream
                    return CompletableFuture.completedFuture(this.buildResponse(localResults));
                }
                
                // Query upstream for additional results
                return this.upstream.response(line, headers, Content.EMPTY)
                    .thenCompose(upstreamResponse -> upstreamResponse.body().asBytesFuture()
                    .thenApply(upstreamBytes -> {
                        // Merge local and upstream results
                        return this.mergeResults(
                            localResults,
                            new String(upstreamBytes, StandardCharsets.UTF_8),
                            size,
                            from
                        );
                    })
                ).exceptionally(err -> {
                    // If upstream fails, just return local results
                    return this.buildResponse(localResults);
                });
            });
    }
    
    /**
     * Merge local and upstream results.
     * @param localResults Local results
     * @param upstreamJson Upstream JSON response
     * @param size Maximum results
     * @param from Offset
     * @return Merged response
     */
    private Response mergeResults(
        final List<PackageMetadata> localResults,
        final String upstreamJson,
        final int size,
        final int from
    ) {
        final Set<String> seenNames = new HashSet<>();
        final List<PackageMetadata> merged = new ArrayList<>(localResults);
        
        // Track local package names
        localResults.forEach(pkg -> seenNames.add(pkg.name()));
        
        try {
            final JsonObject upstreamResponse = Json.createReader(
                new StringReader(upstreamJson)
            ).readObject();
            
            final JsonArray upstreamObjects = upstreamResponse.getJsonArray("objects");
            if (upstreamObjects != null) {
                for (int i = 0; i < upstreamObjects.size() && merged.size() < size; i++) {
                    final JsonObject obj = upstreamObjects.getJsonObject(i);
                    final JsonObject pkg = obj.getJsonObject("package");
                    final String name = pkg.getString("name", "");
                    
                    // Add only if not already in local results
                    if (!seenNames.contains(name)) {
                        merged.add(new PackageMetadata(
                            name,
                            pkg.getString("version", ""),
                            pkg.getString("description", ""),
                            this.extractKeywords(pkg)
                        ));
                        seenNames.add(name);
                    }
                }
            }
        } catch (Exception e) {
            // If parsing fails, just use local results
        }
        
        return this.buildResponse(merged.subList(
            Math.min(from, merged.size()),
            Math.min(from + size, merged.size())
        ));
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
