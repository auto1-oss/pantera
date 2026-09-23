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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Go mod slice: this slice returns json-formatted metadata about go module as
 * described in "JSON-formatted metadata(.info file body) about the latest known version"
 * section of readme.
 */
public final class LatestSlice implements Slice {

    /**
     * {@code .info} file directly under an {@code @v} directory; group 1 is the version.
     */
    private static final Pattern INFO = Pattern.compile("^.*/@v/([^/]+)\\.info$");

    private final Storage storage;

    /**
     * @param storage Storage
     */
    public LatestSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        String path = LatestSlice.normalized(line);
        return this.storage.list(new KeyFromPath(path))
            .thenCompose(this::resp);
    }

    /**
     * Replaces the word latest if it is the last part of the URI path, by v. Then returns the path.
     * @param line Received request line
     * @return A URI path with replaced latest.
     */
    private static String normalized(final RequestLine line) {
        final URI received = line.uri();
        String path = received.getPath();
        final String latest = "latest";
        if (path.endsWith(latest)) {
            path = path.substring(0, path.lastIndexOf(latest)).concat("v");
        }
        return path;
    }

    /**
     * Composes response. It takes the {@code .info} files directly under the
     * module's {@code @v} directory, chooses the latest version by Go version
     * ordering (highest release, else highest prerelease, else highest
     * pseudo-version) and returns content from its {@code .info} file.
     * @param module Module file names list from repository
     * @return Response
     */
    private CompletableFuture<Response> resp(final Collection<Key> module) {
        final Map<String, Key> infos = new HashMap<>();
        for (final Key key : module) {
            final Matcher matcher = LatestSlice.INFO.matcher(key.string());
            if (matcher.matches()) {
                infos.put(matcher.group(1), key);
            }
        }
        final Optional<String> latest = new GoVersionOrder().latest(infos.keySet());
        if (latest.isPresent()) {
            return this.storage.value(infos.get(latest.get()))
                .thenApply(c -> ResponseBuilder.ok()
                    .header(ContentType.json())
                    .body(c)
                    .build());
        }
        return ResponseBuilder.notFound().completedFuture();
    }
}
