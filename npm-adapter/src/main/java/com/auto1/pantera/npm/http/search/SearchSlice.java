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
package com.auto1.pantera.npm.http.search;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;



import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

/**
 * Search slice - handles npm search.
 * Endpoint: GET /-/v1/search?text={query}&size={n}&from={offset}
 *
 * @since 1.1
 */
public final class SearchSlice implements Slice {
    
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
     * Storage.
     */
    private final Storage storage;
    
    /**
     * Package index.
     */
    private final PackageIndex index;
    
    /**
     * Constructor.
     * @param storage Storage
     * @param index Package index
     */
    public SearchSlice(final Storage storage, final PackageIndex index) {
        this.storage = storage;
        this.index = index;
    }
    
    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
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

            return this.index.search(text, size, from)
                .thenApply(results -> {
                    final JsonArrayBuilder objects = Json.createArrayBuilder();
                    results.forEach(pkg -> objects.add(this.packageToJson(pkg)));

                    return ResponseBuilder.ok()
                        .jsonBody(Json.createObjectBuilder()
                            .add("objects", objects)
                            .add("total", results.size())
                            .add("time", System.currentTimeMillis())
                            .build())
                        .build();
                });
        });
    }
    
    /**
     * Convert package to JSON result.
     * @param pkg Package metadata
     * @return JSON object builder
     */
    private JsonObjectBuilder packageToJson(final PackageMetadata pkg) {
        final JsonArrayBuilder keywords = Json.createArrayBuilder();
        pkg.keywords().forEach(keywords::add);
        
        return Json.createObjectBuilder()
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
            .add("searchScore", 1.0);
    }
}
