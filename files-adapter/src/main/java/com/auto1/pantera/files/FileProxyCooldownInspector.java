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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

/**
 * Release date of a proxied file: the upstream's {@code Last-Modified}.
 *
 * <p>file-proxy cooldown is evaluated before the file is fetched (a cache
 * miss), and nothing else ever records a date for a file path — the
 * publish-date registry has no file source and the artifact events carry no
 * release date. The only evidence of a file's age is the upstream's
 * {@code Last-Modified}, so this inspector asks for it with a {@code HEAD}
 * of the same path. It is only consulted when no cooldown row exists yet,
 * so an existing block answers without an upstream round trip. When the
 * upstream gives no usable date it falls back to the given inspector.</p>
 *
 * @since 2.2.9
 */
final class FileProxyCooldownInspector implements CooldownInspector {

    /**
     * Upstream slice.
     */
    private final Slice remote;

    /**
     * Fallback when the upstream reports no date.
     */
    private final CooldownInspector fallback;

    /**
     * Ctor.
     *
     * @param remote Upstream slice
     * @param fallback Fallback inspector
     */
    FileProxyCooldownInspector(final Slice remote, final CooldownInspector fallback) {
        this.remote = remote;
        this.fallback = fallback;
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(
        final String artifact, final String version
    ) {
        final CompletableFuture<Optional<Instant>> head;
        try {
            head = this.remote.response(
                new RequestLine(RqMethod.HEAD, artifact), Headers.EMPTY, Content.EMPTY
            ).thenCompose(
                resp -> resp.body().asBytesFuture().thenApply(
                    ignored -> resp.status().success()
                        ? lastModified(resp.headers()) : Optional.<Instant>empty()
                )
            ).exceptionally(err -> Optional.empty());
        } catch (final RuntimeException err) {
            return this.fallback.releaseDate(artifact, version);
        }
        return head.thenCompose(
            date -> date.isPresent()
                ? CompletableFuture.completedFuture(date)
                : this.fallback.releaseDate(artifact, version)
        );
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(
        final String artifact, final String version
    ) {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * RFC 1123 {@code Last-Modified} header value.
     *
     * @param headers Response headers
     * @return Parsed instant, empty when absent or malformed
     */
    private static Optional<Instant> lastModified(final Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst()
            .flatMap(value -> {
                try {
                    return Optional.of(
                        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value))
                    );
                } catch (final DateTimeParseException ex) {
                    return Optional.empty();
                }
            });
    }
}
