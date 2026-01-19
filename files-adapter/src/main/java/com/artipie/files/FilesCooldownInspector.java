/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.files;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;

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
        return this.remote.response(new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, com.artipie.asto.Content.EMPTY)
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
                } catch (final DateTimeParseException ignored) {
                    return Optional.empty();
                }
            });
    }
}
