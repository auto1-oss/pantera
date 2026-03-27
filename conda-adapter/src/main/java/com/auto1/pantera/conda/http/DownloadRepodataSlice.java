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
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentFileName;
import com.auto1.pantera.http.rq.RequestLine;

import javax.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice to download repodata.json. If the repodata item does not exists in storage, empty
 * json is returned.
 */
public final class DownloadRepodataSlice implements Slice {

    /**
     * Request path pattern.
     */
    private static final Pattern RQ_PATH = Pattern.compile(".*/((.+)/(current_)?repodata\\.json)");

    private final Storage asto;

    /**
     * @param asto Abstract storage
     */
    public DownloadRepodataSlice(final Storage asto) {
        this.asto = asto;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body) {
        String path = line.uri().getPath();

        final Matcher matcher = DownloadRepodataSlice.RQ_PATH.matcher(path);
        if (matcher.matches()) {
            final Key key = new Key.From(matcher.group(1));
            return this.asto.exists(key).thenCompose(
                exist -> {
                    if (exist) {
                        return this.asto.value(key);
                    }
                    return CompletableFuture.completedFuture(
                        new Content.From(
                            Json.createObjectBuilder().add(
                                    "info", Json.createObjectBuilder()
                                        .add("subdir", matcher.group(2))
                                ).build().toString()
                                .getBytes(StandardCharsets.US_ASCII)
                        )
                    );
                }
            ).thenApply(
                content -> ResponseBuilder.ok()
                    .header(new ContentFileName(new KeyLastPart(key).get()))
                    .body(content)
                    .build()
            );
        }
        return ResponseBuilder.badRequest().completedFuture();
    }
}
