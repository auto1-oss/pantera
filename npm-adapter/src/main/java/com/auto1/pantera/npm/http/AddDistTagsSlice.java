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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.PerVersionLayout;

import javax.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice that adds/updates a dist-tag ({@code npm dist-tag add}).
 *
 * <p>Writes into the per-version layout's durable dist-tags sidecar
 * ({@code <pkg>/.dist-tags.json}) for packages published through the current
 * write path. Falls back to a legacy {@code meta.json} read-modify-write for
 * packages that predate the per-version layout.</p>
 */
final class AddDistTagsSlice implements Slice {

    /**
     * Endpoint request line pattern.
     */
    static final Pattern PTRN = Pattern.compile("/-/package/(?<pkg>.*)/dist-tags/(?<tag>.*)");

    /**
     * Dist-tags json field name.
     */
    private static final String DIST_TAGS = "dist-tags";

    /**
     * Abstract storage.
     */
    private final Storage storage;

    /**
     * @param storage Abstract storage
     */
    AddDistTagsSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Matcher matcher = AddDistTagsSlice.PTRN.matcher(line.uri().getPath());
        if (!matcher.matches()) {
            // Consume request body to prevent Vert.x resource leak
            return body.asBytesFuture().thenApply(ignored -> ResponseBuilder.badRequest().build());
        }
        final Key packageKey = new Key.From(matcher.group("pkg"));
        final String tag = matcher.group("tag");
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        return new Content.From(body).asStringFuture().thenCompose(
            raw -> {
                final String version = raw.replaceAll("\"", "");
                return layout.hasVersions(packageKey).thenCompose(
                    hasVersions -> {
                        if (hasVersions) {
                            return layout.writeTag(packageKey, tag, version)
                                .thenApply(ignored -> ResponseBuilder.ok().build());
                        }
                        return this.legacyAdd(packageKey, tag, version);
                    }
                );
            }
        );
    }

    /**
     * Legacy fallback for packages published before the per-version layout
     * existed: read-modify-write {@code dist-tags} straight in {@code meta.json}.
     *
     * @param packageKey Package key
     * @param tag Tag name
     * @param version Version the tag should point to
     * @return Completion stage with the response
     */
    private CompletableFuture<Response> legacyAdd(
        final Key packageKey, final String tag, final String version
    ) {
        final Key meta = new Key.From(packageKey, "meta.json");
        return this.storage.exists(meta).thenCompose(
            exists -> {
                if (exists) {
                    return this.storage.value(meta)
                        .thenCompose(Content::asJsonObjectFuture)
                        .thenApply(json -> Json.createObjectBuilder(json).add(
                            AddDistTagsSlice.DIST_TAGS,
                            Json.createObjectBuilder()
                                .addAll(
                                    Json.createObjectBuilder(
                                        json.getJsonObject(AddDistTagsSlice.DIST_TAGS)
                                    )
                                ).add(tag, version)
                        ).build())
                        .thenCompose(
                            json -> {
                                final byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                                return this.storage.save(meta, new Content.From(bytes))
                                    .thenApply(unused -> ResponseBuilder.ok().build());
                            }
                        );
                }
                return ResponseBuilder.notFound().completedFuture();
            }
        );
    }
}
