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
package com.auto1.pantera.files;

import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

/**
 * Cooldown inspector for file-proxy repositories.
 * Uses HEAD request to retrieve Last-Modified header as release date.
 */
final class FilesCooldownInspector implements CooldownInspector {

    private final Slice remote;

    FilesCooldownInspector(final Slice remote) {
        this.remote = remote;
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        final String path = artifact; // artifact is the full upstream path for files
        return this.remote.response(new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, com.auto1.pantera.asto.Content.EMPTY)
            .thenApply(response -> {
                if (!response.status().success()) {
                    return Optional.empty();
                }
                return lastModified(response.headers());
            });
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    private static Optional<Instant> lastModified(final Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst()
            .flatMap(value -> {
                try {
                    return Optional.of(Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)));
                } catch (final DateTimeParseException ex) {
                    EcsLogger.debug("com.auto1.pantera.files")
                        .message("Failed to parse Last-Modified header")
                        .error(ex)
                        .log();
                    return Optional.empty();
                }
            });
    }
}
