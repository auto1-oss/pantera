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
package com.auto1.pantera.docker.http.upload;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.error.NonContiguousChunkException;
import com.auto1.pantera.docker.error.UploadUnknownError;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.ContentWithSize;

import java.security.Permission;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class PatchUploadSlice extends UploadSlice {

    /**
     * Header a chunked client uses to declare where, within the final
     * assembled blob, this chunk starts — {@code <start>-<end>} per the
     * Distribution spec (no {@code bytes=} prefix, unlike the request
     * {@code Range} header), optionally tolerating one anyway.
     */
    private static final String CONTENT_RANGE = "Content-Range";

    public PatchUploadSlice(Docker docker) {
        super(docker);
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), UploadRequest.from(line).name(), DockerActions.PUSH.mask()
        );
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        UploadRequest request = UploadRequest.from(line);
        final Optional<Long> declaredStart = declaredChunkStart(headers);
        final CompletableFuture<Response> result = this.docker.repo(request.name())
            .uploads()
            .get(request.uuid())
            .thenCompose(
                found -> found.map(
                    upload -> upload
                        .append(new ContentWithSize(body, headers), declaredStart)
                        .thenCompose(offset -> acceptedResponse(request.name(), request.uuid(), offset))
                ).orElseGet(
                    () -> ResponseBuilder.notFound()
                        .jsonBody(new UploadUnknownError(request.uuid()).json())
                        .completedFuture()
                )
            );
        return result.exceptionally(err -> {
            final Throwable cause = rootCause(err);
            if (cause instanceof NonContiguousChunkException nonContiguous) {
                return ResponseBuilder.rangeNotSatisfiable()
                    .jsonBody(nonContiguous.json())
                    .build();
            }
            throw new CompletionException(cause);
        });
    }

    /**
     * Parses the client-declared chunk start offset from {@code
     * Content-Range}, when present.
     *
     * @param headers Request headers.
     * @return Declared start offset, empty when the header is absent or
     *         unparseable (legacy monolithic-PATCH clients send neither).
     */
    private static Optional<Long> declaredChunkStart(final Headers headers) {
        final List<String> values = headers.values(CONTENT_RANGE);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        final String raw = values.get(0).trim();
        final String stripped = raw.regionMatches(true, 0, "bytes ", 0, 6)
            ? raw.substring(6).trim() : raw;
        final int sep = stripped.indexOf('-');
        if (sep < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(stripped.substring(0, sep).trim()));
        } catch (final NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Unwraps nested {@link CompletionException}/{@link
     * java.util.concurrent.ExecutionException} layers to find the
     * originating cause.
     *
     * @param ex Throwable to unwrap.
     * @return Root cause.
     */
    private static Throwable rootCause(final Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null
            && cause.getCause() != cause) { // NOPMD CompareObjectsWithEquals - intentional identity check (cycle guard for self-causing exception)
            cause = cause.getCause();
        }
        return cause;
    }
}
