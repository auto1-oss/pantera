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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.PackageNameFromUrl;
import org.apache.commons.lang3.StringUtils;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonPatchBuilder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Slice to handle `npm deprecate` command requests.
 */
public final class DeprecateSlice implements Slice {
    /**
     * Patter for `referer` header value.
     */
    static final Pattern HEADER = Pattern.compile("deprecate.*");

    /**
     * Abstract storage.
     */
    private final Storage storage;

    /**
     * @param storage Abstract storage
     */
    public DeprecateSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers iterable, Content publisher) {
        final String pkg = new PackageNameFromUrl(line).value();
        final Key key = new Key.From(pkg, "meta.json");
        return this.storage.exists(key).thenCompose(
            exists -> {
                if (exists) {
                    return new Content.From(publisher).asJsonObjectFuture()
                        .thenApply(json -> json.getJsonObject("versions"))
                        .thenCombine(
                            this.storage.value(key)
                                .thenCompose(Content::asJsonObjectFuture),
                            (body, meta) -> DeprecateSlice.deprecate(body, meta).toString()
                        ).thenApply(
                            str -> {
                                this.storage.save(
                                    key, new Content.From(str.getBytes(StandardCharsets.UTF_8))
                                );
                                return ResponseBuilder.ok().build();
                            }
                        );
                }
                // Consume request body to prevent Vert.x request leak
                return new Content.From(publisher).asBytesFuture().thenApply(ignored ->
                    ResponseBuilder.notFound().build()
                );
            }
        );
    }

    /**
     * Adds tag deprecated from request body to meta.json.
     * @param versions Versions json
     * @param meta Meta json from storage
     * @return Meta json with added deprecate tags
     */
    private static JsonObject deprecate(final JsonObject versions, final JsonObject meta) {
        final JsonPatchBuilder res = Json.createPatchBuilder();
        final String field = "deprecated";
        final  String path = "/versions/%s/deprecated";
        for (final String version : versions.keySet()) {
            if (versions.getJsonObject(version).containsKey(field)) {
                if (StringUtils.isEmpty(versions.getJsonObject(version).getString(field))) {
                    res.remove(String.format(path, version));
                } else {
                    res.add(
                        String.format(path, version),
                        versions.getJsonObject(version).getString(field)
                    );
                }
            }
        }
        return res.build().apply(meta);
    }
}
