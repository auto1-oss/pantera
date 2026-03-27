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
import com.auto1.pantera.docker.error.UploadUnknownError;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class GetUploadSlice extends UploadSlice {

    public GetUploadSlice(Docker docker) {
        super(docker);
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), UploadRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        UploadRequest request = UploadRequest.from(line);
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(request.name())
                .uploads()
                .get(request.uuid())
                .thenApply(
                    found -> found.map(
                        upload -> upload.offset()
                            .thenApply(
                                offset -> ResponseBuilder.noContent()
                                    .header(new ContentLength("0"))
                                    .header(new Header("Range", String.format("0-%d", offset)))
                                    .header(new Header("Docker-Upload-UUID", request.uuid()))
                                    .build()
                            )
                    ).orElseGet(
                        () -> ResponseBuilder.notFound()
                            .jsonBody(new UploadUnknownError(request.uuid()).json())
                            .completedFuture()
                    )
                ).thenCompose(Function.identity())
        );
    }
}
