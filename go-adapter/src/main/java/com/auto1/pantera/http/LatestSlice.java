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
import com.auto1.pantera.cooldown.metadata.VersionComparators;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.net.URI;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Go mod slice: this slice returns json-formatted metadata about go module as
 * described in "JSON-formatted metadata(.info file body) about the latest known version"
 * section of readme.
 */
public final class LatestSlice implements Slice {

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
     * Composes response. It filters .info files from module directory, chooses the greatest
     * version by {@link VersionComparators#semver()} (NOT lexicographic string order — plain
     * string comparison would rank {@code v0.9.0} above {@code v0.10.0}), and returns content
     * from the .info file.
     * @param module Module file names list from repository
     * @return Response
     */
    private CompletableFuture<Response> resp(final Collection<Key> module) {
        final Optional<String> info = module.stream().map(Key::string)
            .filter(item -> item.endsWith("info"))
            .max(Comparator.comparing(LatestSlice::version, VersionComparators.semver()));
        if (info.isPresent()) {
            return this.storage.value(new KeyFromPath(info.get()))
                .thenApply(c -> ResponseBuilder.ok()
                    .header(ContentType.json())
                    .body(c)
                    .build());
        }
        return ResponseBuilder.notFound().completedFuture();
    }

    /**
     * Extracts the version token from a {@code .info} storage key
     * ({@code module/path/@v/v1.2.3.info} &rarr; {@code v1.2.3}) so it can be
     * compared with {@link VersionComparators#semver()}, which tolerates the
     * leading {@code v}.
     * @param key Storage key string
     * @return Version token
     */
    private static String version(final String key) {
        final String filename = key.substring(key.lastIndexOf('/') + 1);
        final String suffix = ".info";
        return filename.endsWith(suffix)
            ? filename.substring(0, filename.length() - suffix.length())
            : filename;
    }
}
