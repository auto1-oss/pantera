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
import com.auto1.pantera.docker.asto.UploadRangeException;
import com.auto1.pantera.docker.error.UploadInvalidError;
import com.auto1.pantera.docker.error.UploadUnknownError;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.ContentWithSize;

import java.security.Permission;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PatchUploadSlice extends UploadSlice {

    /**
     * {@code Content-Range} of a chunk.
     */
    private static final Pattern RANGE =
        Pattern.compile("(?:bytes[ =])?(\\d{1,18})-\\d{1,18}(?:/(?:\\d+|\\*))?");

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
        return this.docker.repo(request.name())
            .uploads()
            .get(request.uuid())
            .thenCompose(
                found -> found.map(
                    upload -> upload
                        .append(new ContentWithSize(body, headers), PatchUploadSlice.start(headers))
                        .thenCompose(offset -> acceptedResponse(request.name(), request.uuid(), offset))
                        .exceptionally(err -> this.outOfOrder(request, err))
                ).orElseGet(
                    () -> body.discard().thenApply(
                        ignored -> ResponseBuilder.notFound()
                            .jsonBody(new UploadUnknownError(request.uuid()).json())
                            .build()
                    )
                )
            );
    }

    /**
     * Map an out-of-order chunk to 416 with the range the registry holds;
     * rethrow anything else.
     *
     * @param request Upload request
     * @param err Failure
     * @return 416 response
     */
    private Response outOfOrder(final UploadRequest request, final Throwable err) {
        final Throwable cause = err instanceof CompletionException && err.getCause() != null
            ? err.getCause() : err;
        if (cause instanceof UploadRangeException range) {
            return ResponseBuilder.rangeNotSatisfiable()
                .header(new Location(
                    String.format("/v2/%s/blobs/uploads/%s", request.name(), request.uuid())
                ))
                .header(new Header("Range", String.format("0-%d", range.offset())))
                .header(new Header("Docker-Upload-UUID", request.uuid()))
                .jsonBody(new UploadInvalidError(range.getMessage()).json())
                .build();
        }
        throw new CompletionException(cause);
    }

    /**
     * Declared start offset of the chunk from {@code Content-Range}
     * ({@code <start>-<end>}, optionally prefixed with {@code bytes} and
     * suffixed with {@code /<size>}). A header that does not parse can
     * never match the current offset, so it is answered with 416 too.
     *
     * @param headers Request headers
     * @return Start offset, empty without the header
     */
    private static Optional<Long> start(final Headers headers) {
        return headers.values("Content-Range").stream().findFirst().map(
            value -> {
                final Matcher matcher = PatchUploadSlice.RANGE.matcher(value.trim());
                return matcher.matches() ? Long.parseLong(matcher.group(1)) : -1L;
            }
        );
    }
}
