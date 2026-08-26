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

/**
 * Slice that removes a dist-tag ({@code npm dist-tag rm}).
 *
 * <p>Writes into the per-version layout's durable dist-tags sidecar
 * ({@code <pkg>/.dist-tags.json}) for packages published through the current
 * write path. Falls back to a legacy {@code meta.json} read-modify-write for
 * packages that predate the per-version layout.</p>
 */
public final class DeleteDistTagsSlice implements Slice {

    /**
     * Dist-tags json field name.
     */
    private static final String FIELD = "dist-tags";

    private final Storage storage;

    /**
     * @param storage Abstract storage
     */
    public DeleteDistTagsSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers iterable, Content body) {
        final Matcher matcher = AddDistTagsSlice.PTRN.matcher(line.uri().getPath());
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            if (!matcher.matches()) {
                return ResponseBuilder.badRequest().completedFuture();
            }
            final Key packageKey = new Key.From(matcher.group("pkg"));
            final String tag = matcher.group("tag");
            final PerVersionLayout layout = new PerVersionLayout(this.storage);
            return layout.hasVersions(packageKey).thenCompose(
                hasVersions -> {
                    if (hasVersions) {
                        return layout.removeTag(packageKey, tag)
                            .thenApply(unused -> ResponseBuilder.ok().build());
                    }
                    return this.legacyRemove(packageKey, tag);
                }
            );
        });
    }

    /**
     * Legacy fallback for packages published before the per-version layout
     * existed: read-modify-write {@code dist-tags} straight in {@code meta.json}.
     *
     * @param packageKey Package key
     * @param tag Tag name to remove
     * @return Completion stage with the response
     */
    private CompletableFuture<Response> legacyRemove(final Key packageKey, final String tag) {
        final Key meta = new Key.From(packageKey, "meta.json");
        return this.storage.exists(meta).thenCompose(
            exists -> {
                if (exists) {
                    return this.storage.value(meta)
                        .thenCompose(Content::asJsonObjectFuture)
                        .thenApply(
                            json -> Json.createObjectBuilder(json).add(
                                DeleteDistTagsSlice.FIELD,
                                Json.createObjectBuilder()
                                    .addAll(
                                        Json.createObjectBuilder(
                                            json.getJsonObject(DeleteDistTagsSlice.FIELD)
                                        )
                                    ).remove(tag)
                            ).build()
                        ).thenApply(
                            json -> json.toString().getBytes(StandardCharsets.UTF_8)
                        ).thenCompose(
                            bytes -> this.storage.save(meta, new Content.From(bytes))
                                .thenApply(unused -> ResponseBuilder.ok().build())
                        );
                }
                return ResponseBuilder.notFound().completedFuture();
            }
        );
    }
}
